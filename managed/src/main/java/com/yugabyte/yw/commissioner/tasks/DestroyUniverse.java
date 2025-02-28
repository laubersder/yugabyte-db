/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.TaskExecutor.SubTaskGroup;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteCertificate;
import com.yugabyte.yw.commissioner.tasks.subtasks.RemoveUniverseEntry;
import com.yugabyte.yw.common.DnsManager;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.XClusterConfig;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DestroyUniverse extends UniverseTaskBase {

  private Set<UUID> lockedXClusterUniversesUuidSet = null;

  @Inject
  public DestroyUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  public static class Params extends UniverseTaskParams {
    public UUID customerUUID;
    public Boolean isForceDelete;
    public Boolean isDeleteBackups;
    public Boolean isDeleteAssociatedCerts;
  }

  public Params params() {
    return (Params) taskParams;
  }

  @Override
  public void run() {
    try {
      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      Universe universe;
      if (params().isForceDelete) {
        universe = forceLockUniverseForUpdate(-1, true);
      } else {
        universe = lockUniverseForUpdate(-1, true);
      }

      // Delete xCluster configs involving this universe and put the locked universes to
      // lockedUniversesUuidList.
      createDeleteXClusterConfigSubtasksAndLockOtherUniverses();

      if (params().isDeleteBackups) {
        List<Backup> backupList =
            Backup.fetchBackupToDeleteByUniverseUUID(params().customerUUID, universe.universeUUID);
        createDeleteBackupYbTasks(backupList, params().customerUUID)
            .setSubTaskGroupType(SubTaskGroupType.DeletingBackup);
      }

      preTaskActions();

      // Cleanup the kms_history table
      createDestroyEncryptionAtRestTask()
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      if (!universe.getUniverseDetails().isImportedUniverse()) {
        // Update the DNS entry for primary cluster to mirror creation.
        Cluster primaryCluster = universe.getUniverseDetails().getPrimaryCluster();
        createDnsManipulationTask(
                DnsManager.DnsCommandType.Delete, params().isForceDelete, primaryCluster.userIntent)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

        if (primaryCluster.userIntent.providerType.equals(CloudType.onprem)) {
          // Stop master and tservers.
          createStopServerTasks(universe.getNodes(), "master", params().isForceDelete)
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
          createStopServerTasks(universe.getNodes(), "tserver", params().isForceDelete)
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
        }

        // Set the node states to Removing.
        createSetNodeStateTasks(universe.getNodes(), NodeDetails.NodeState.Terminating)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
        // Create tasks to destroy the existing nodes.
        createDestroyServerTasks(
                universe.getNodes(),
                params().isForceDelete,
                true /* delete node */,
                true /* deleteRootVolumes */)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
      }

      // Create tasks to remove the universe entry from the Universe table.
      createRemoveUniverseEntryTask().setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      // Update the swamper target file.
      createSwamperTargetUpdateTask(true /* removeFile */);

      if (params().isDeleteAssociatedCerts) {
        createDeleteCertificatesTaskGroup(universe.getUniverseDetails())
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
      }

      // Run all the tasks.
      getRunnableTask().runSubTasks();
    } catch (Throwable t) {
      // If for any reason destroy fails we would just unlock the universe for update
      try {
        unlockUniverseForUpdate();
      } catch (Throwable t1) {
        // Ignore the error
      }
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      throw t;
    } finally {
      unlockXClusterUniverses(lockedXClusterUniversesUuidSet, params().isForceDelete);
    }
    log.info("Finished {} task.", getName());
  }

  public SubTaskGroup createRemoveUniverseEntryTask() {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("RemoveUniverseEntry", executor);
    Params params = new Params();
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    params.customerUUID = params().customerUUID;
    params.isForceDelete = params().isForceDelete;

    // Create the Ansible task to destroy the server.
    RemoveUniverseEntry task = createTask(RemoveUniverseEntry.class);
    task.initialize(params);
    // Add it to the task list.
    subTaskGroup.addSubTask(task);
    getRunnableTask().addSubTaskGroup(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createDeleteCertificatesTaskGroup(
      UniverseDefinitionTaskParams universeDetails) {
    SubTaskGroup subTaskGroup =
        getTaskExecutor().createSubTaskGroup("DeleteCertificates", executor);

    // Create the task to delete rootCerts.
    DeleteCertificate rootCertDeletiontask =
        createDeleteCertificateTask(params().customerUUID, universeDetails.rootCA);
    // Add it to the task list.
    if (rootCertDeletiontask != null) {
      subTaskGroup.addSubTask(rootCertDeletiontask);
    }

    if (!universeDetails.rootAndClientRootCASame) {
      // Create the task to delete clientRootCerts.
      DeleteCertificate clientRootCertDeletiontask =
          createDeleteCertificateTask(params().customerUUID, universeDetails.clientRootCA);
      // Add it to the task list.
      if (clientRootCertDeletiontask != null) {
        subTaskGroup.addSubTask(clientRootCertDeletiontask);
      }
    }

    if (subTaskGroup.getSubTaskCount() > 0) {
      getRunnableTask().addSubTaskGroup(subTaskGroup);
    }
    return subTaskGroup;
  }

  public DeleteCertificate createDeleteCertificateTask(UUID customerUUID, UUID certUUID) {
    DeleteCertificate.Params params = new DeleteCertificate.Params();
    params.customerUUID = customerUUID;
    params.certUUID = certUUID;
    DeleteCertificate task = createTask(DeleteCertificate.class);
    task.initialize(params);
    return task;
  }

  /**
   * It deletes all the xCluster configs that the universe belonging to the DestroyUniverse task is
   * one side of.
   *
   * <p>Note: it relies on the fact that the unlock universe operation on a universe that is not
   * locked by this task is a no-op.
   */
  private void createDeleteXClusterConfigSubtasksAndLockOtherUniverses() {
    // XCluster configs whose other universe exists.
    List<XClusterConfig> xClusterConfigs =
        XClusterConfig.getByUniverseUuid(params().universeUUID)
            .stream()
            .filter(
                xClusterConfig ->
                    xClusterConfig.status
                        != XClusterConfig.XClusterConfigStatusType.DeletedUniverse)
            .collect(Collectors.toList());

    // Set xCluster configs status to DeleteUniverse. We do not use the corresponding subtask to
    // do this because it might get into an error until that point, but we want to set this state
    // even when there is an error.
    xClusterConfigs.forEach(
        xClusterConfig -> {
          xClusterConfig.setStatus(XClusterConfig.XClusterConfigStatusType.DeletedUniverse);
        });

    Map<UUID, List<XClusterConfig>> otherUniverseUuidToXClusterConfigsMap =
        xClusterConfigs
            .stream()
            .collect(
                Collectors.groupingBy(
                    xClusterConfig -> {
                      if (xClusterConfig.sourceUniverseUUID.equals(params().universeUUID)) {
                        // Case 1: Delete xCluster configs where this universe is the source
                        // universe.
                        return xClusterConfig.targetUniverseUUID;
                      } else {
                        // Case 2: Delete xCluster configs where this universe is the target
                        // universe.
                        return xClusterConfig.sourceUniverseUUID;
                      }
                    }));

    // Put all the universes in the locked list. The unlock operation is a no-op if the universe
    // does not get locked by this task.
    lockedXClusterUniversesUuidSet = otherUniverseUuidToXClusterConfigsMap.keySet();

    // Create the subtasks to delete the xCluster configs.
    otherUniverseUuidToXClusterConfigsMap.forEach(
        this::createDeleteXClusterConfigSubtasksAndLockOtherUniverse);
  }

  /**
   * It deletes all the xCluster configs between the universe in this task and the universe whose
   * UUID is specified in the {@code otherUniverseUuid} parameter.
   *
   * @param otherUniverseUuid The UUID of the universe on the other side of xCluster replication
   * @param xClusterConfigs The list of the xCluster configs to delete; they must be between the
   *     universe that belongs to this task and the universe whose UUID is specified in the {@code
   *     otherUniverseUuid} parameter
   */
  private void createDeleteXClusterConfigSubtasksAndLockOtherUniverse(
      UUID otherUniverseUuid, List<XClusterConfig> xClusterConfigs) {
    try {
      // Lock the other universe.
      if (lockUniverseIfExist(otherUniverseUuid, -1 /* expectedUniverseVersion */) == null) {
        log.info("Other universe is deleted; No further action is needed");
        return;
      }

      // Create the subtasks to delete all the xCluster configs.
      xClusterConfigs.forEach(this::createDeleteXClusterConfigSubtasks);
      log.debug("Subtasks created to delete these xCluster configs: {}", xClusterConfigs);
    } catch (Exception e) {
      log.error(
          "{} hit error while creating subtasks for xCluster config deletion : {}",
          getName(),
          e.getMessage());
      // If this task is force delete, ignore errors.
      if (!params().isForceDelete) {
        throw new RuntimeException(e);
      } else {
        log.debug("Error ignored because isForceDelete is true");
      }
    }
  }
}
