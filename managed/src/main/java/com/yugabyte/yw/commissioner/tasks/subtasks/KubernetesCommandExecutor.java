/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks.subtasks;

import static com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ExposingServiceState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.commissioner.tasks.UniverseTaskBase;
import com.yugabyte.yw.commissioner.tasks.XClusterConfigTaskBase;
import com.yugabyte.yw.common.KubernetesManagerFactory;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.certmgmt.CertificateDetails;
import com.yugabyte.yw.common.certmgmt.CertificateHelper;
import com.yugabyte.yw.common.certmgmt.EncryptionInTransitUtil;
import com.yugabyte.yw.common.certmgmt.providers.CertificateProviderInterface;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.x509.GeneralName;
import org.yaml.snakeyaml.Yaml;
import play.libs.Json;

@Slf4j
public class KubernetesCommandExecutor extends UniverseTaskBase {

  public enum CommandType {
    CREATE_NAMESPACE,
    APPLY_SECRET,
    HELM_INIT,
    HELM_INSTALL,
    HELM_UPGRADE,
    // TODO(bhavin192): should we just deprecate this? It is not used
    // anywhere in the code, and we use Helm operations to modify the
    // number of TServer nodes. The code which was using it has been
    // removed 3 years back in
    // 6c757362e4ba55921963e34c01e382f48843d959.
    UPDATE_NUM_NODES,
    HELM_DELETE,
    VOLUME_DELETE,
    NAMESPACE_DELETE,
    POD_INFO,
    // The following flag is deprecated.
    INIT_YSQL;

    public String getSubTaskGroupName() {
      switch (this) {
        case CREATE_NAMESPACE:
          return UserTaskDetails.SubTaskGroupType.CreateNamespace.name();
        case APPLY_SECRET:
          return UserTaskDetails.SubTaskGroupType.ApplySecret.name();
        case HELM_INSTALL:
          return UserTaskDetails.SubTaskGroupType.HelmInstall.name();
        case HELM_UPGRADE:
          return UserTaskDetails.SubTaskGroupType.HelmUpgrade.name();
        case UPDATE_NUM_NODES:
          return UserTaskDetails.SubTaskGroupType.UpdateNumNodes.name();
        case HELM_DELETE:
          return UserTaskDetails.SubTaskGroupType.HelmDelete.name();
        case VOLUME_DELETE:
          return UserTaskDetails.SubTaskGroupType.KubernetesVolumeDelete.name();
        case NAMESPACE_DELETE:
          return UserTaskDetails.SubTaskGroupType.KubernetesNamespaceDelete.name();
        case POD_INFO:
          return UserTaskDetails.SubTaskGroupType.KubernetesPodInfo.name();
        case INIT_YSQL:
          return UserTaskDetails.SubTaskGroupType.KubernetesInitYSQL.name();
      }
      return null;
    }
  }

  private final KubernetesManagerFactory kubernetesManagerFactory;

  @Inject
  protected KubernetesCommandExecutor(
      BaseTaskDependencies baseTaskDependencies,
      KubernetesManagerFactory kubernetesManagerFactory) {
    super(baseTaskDependencies);
    this.kubernetesManagerFactory = kubernetesManagerFactory;
  }

  static final Pattern nodeNamePattern = Pattern.compile(".*-n(\\d+)+");

  // Added constant to compute CPU burst limit
  static final double burstVal = 1.2;

  public static class Params extends UniverseTaskParams {
    public UUID providerUUID;
    public CommandType commandType;
    public String helmReleaseName;
    public String namespace;
    public boolean isReadOnlyCluster;
    public String ybSoftwareVersion = null;
    public boolean enableNodeToNodeEncrypt = false;
    public boolean enableClientToNodeEncrypt = false;
    public UUID rootCA = null;
    public ServerType serverType = ServerType.EITHER;
    public int tserverPartition = 0;
    public int masterPartition = 0;
    public Map<String, Object> universeOverrides;
    public Map<String, Object> azOverrides;

    // Master addresses in multi-az case (to have control over different deployments).
    public String masterAddresses = null;

    // PlacementInfo to correctly set the placement details on the servers at start
    // as well as to control the replicas for each deployment.
    public PlacementInfo placementInfo = null;

    // The target cluster's config.
    public Map<String, String> config = null;
  }

  protected KubernetesCommandExecutor.Params taskParams() {
    return (KubernetesCommandExecutor.Params) taskParams;
  }

  @Override
  public void run() {
    String overridesFile;

    // In case no config is provided, assume it is at the provider level
    // (for backwards compatibility).
    Map<String, String> config = taskParams().config;
    if (config == null) {
      config = Provider.get(taskParams().providerUUID).getUnmaskedConfig();
    }
    if (taskParams().commandType != CommandType.POD_INFO && taskParams().namespace == null) {
      throw new IllegalArgumentException("namespace can be null only in case of POD_INFO");
    }

    // TODO: add checks for the shell process handler return values.
    switch (taskParams().commandType) {
      case CREATE_NAMESPACE:
        kubernetesManagerFactory.getManager().createNamespace(config, taskParams().namespace);
        break;
      case APPLY_SECRET:
        String pullSecret = this.getPullSecret();
        if (pullSecret != null) {
          kubernetesManagerFactory
              .getManager()
              .applySecret(config, taskParams().namespace, pullSecret);
        } else {
          log.debug("Pull secret is missing, skipping the pull secret creation.");
        }
        break;
      case HELM_INSTALL:
        overridesFile = this.generateHelmOverride();
        kubernetesManagerFactory
            .getManager()
            .helmInstall(
                taskParams().ybSoftwareVersion,
                config,
                taskParams().providerUUID,
                taskParams().helmReleaseName,
                taskParams().namespace,
                overridesFile,
                taskParams().universeOverrides,
                taskParams().azOverrides);
        break;
      case HELM_UPGRADE:
        overridesFile = this.generateHelmOverride();
        kubernetesManagerFactory
            .getManager()
            .helmUpgrade(
                taskParams().ybSoftwareVersion,
                config,
                taskParams().helmReleaseName,
                taskParams().namespace,
                overridesFile,
                taskParams().universeOverrides,
                taskParams().azOverrides);
        break;
      case UPDATE_NUM_NODES:
        int numNodes = this.getNumNodes();
        if (numNodes > 0) {
          boolean newNamingStyle =
              Universe.getOrBadRequest(taskParams().universeUUID)
                  .getUniverseDetails()
                  .useNewHelmNamingStyle;
          kubernetesManagerFactory
              .getManager()
              .updateNumNodes(
                  config,
                  taskParams().helmReleaseName,
                  taskParams().namespace,
                  numNodes,
                  newNamingStyle);
        }
        break;
      case HELM_DELETE:
        kubernetesManagerFactory
            .getManager()
            .helmDelete(config, taskParams().helmReleaseName, taskParams().namespace);
        break;
      case VOLUME_DELETE:
        kubernetesManagerFactory
            .getManager()
            .deleteStorage(config, taskParams().helmReleaseName, taskParams().namespace);
        break;
      case NAMESPACE_DELETE:
        kubernetesManagerFactory.getManager().deleteNamespace(config, taskParams().namespace);
        break;
      case POD_INFO:
        processNodeInfo();
        break;
    }
  }

  private Map<String, String> getClusterIpForLoadBalancer() {
    Universe u = Universe.getOrBadRequest(taskParams().universeUUID);
    PlacementInfo pi = taskParams().placementInfo;

    Map<UUID, Map<String, String>> azToConfig = PlacementInfoUtil.getConfigPerAZ(pi);
    Map<UUID, String> azToDomain = PlacementInfoUtil.getDomainPerAZ(pi);
    boolean isMultiAz = PlacementInfoUtil.isMultiAZ(Provider.get(taskParams().providerUUID));

    Map<String, String> serviceToIP = new HashMap<String, String>();

    for (Entry<UUID, Map<String, String>> entry : azToConfig.entrySet()) {
      UUID azUUID = entry.getKey();
      String azName = AvailabilityZone.get(azUUID).code;
      String regionName = AvailabilityZone.get(azUUID).region.code;
      Map<String, String> config = entry.getValue();

      // TODO(bhavin192): we seem to be iterating over all the AZs
      // here, and still selecting services for only one AZ governed
      // by the taskParams().nodePrefix. Is it even required to
      // iterate in that case?
      List<Service> services =
          kubernetesManagerFactory
              .getManager()
              .getServices(config, taskParams().helmReleaseName, taskParams().namespace);

      services.forEach(
          service -> {
            serviceToIP.put(service.getMetadata().getName(), service.getSpec().getClusterIP());
          });
    }

    return serviceToIP;
  }

  private void processNodeInfo() {
    ObjectNode pods = Json.newObject();
    Universe u = Universe.getOrBadRequest(taskParams().universeUUID);
    UUID placementUuid =
        taskParams().isReadOnlyCluster
            ? u.getUniverseDetails().getReadOnlyClusters().get(0).uuid
            : u.getUniverseDetails().getPrimaryCluster().uuid;
    PlacementInfo pi = taskParams().placementInfo;

    Map<UUID, Map<String, String>> azToConfig = PlacementInfoUtil.getConfigPerAZ(pi);
    Map<UUID, String> azToDomain = PlacementInfoUtil.getDomainPerAZ(pi);
    boolean isMultiAz = PlacementInfoUtil.isMultiAZ(Provider.get(taskParams().providerUUID));
    String nodePrefix = u.getUniverseDetails().nodePrefix;

    for (Entry<UUID, Map<String, String>> entry : azToConfig.entrySet()) {
      UUID azUUID = entry.getKey();
      String azName = AvailabilityZone.get(azUUID).code;
      String regionName = AvailabilityZone.get(azUUID).region.code;
      Map<String, String> config = entry.getValue();

      String helmReleaseName =
          PlacementInfoUtil.getHelmReleaseName(
              isMultiAz, nodePrefix, azName, taskParams().isReadOnlyCluster);
      String namespace =
          PlacementInfoUtil.getKubernetesNamespace(
              isMultiAz,
              nodePrefix,
              azName,
              config,
              u.getUniverseDetails().useNewHelmNamingStyle,
              taskParams().isReadOnlyCluster);

      List<Pod> podInfos =
          kubernetesManagerFactory.getManager().getPodInfos(config, helmReleaseName, namespace);
      for (Pod podInfo : podInfos) {
        ObjectNode pod = Json.newObject();
        pod.put("startTime", podInfo.getStatus().getStartTime());
        pod.put("status", podInfo.getStatus().getPhase());
        pod.put("az_uuid", azUUID.toString());
        pod.put("az_name", azName);
        pod.put("region_name", regionName);
        String hostname = podInfo.getSpec().getHostname();
        pod.put("hostname", hostname);

        int ybIdx = hostname.lastIndexOf("yb-");
        // The Helm full name is added to all the pods by the Helm
        // chart as a prefix, we are removing the yb-<server>-N part
        // from it. It is blank in case of old naming style.
        pod.put("helmFullNameWithSuffix", hostname.substring(0, ybIdx));
        // We leave out the Helm name prefix from the pod hostname,
        // and use the name like yb-<server>-N[_<az-name>] as nodeName
        // i.e. yb-master-0, and yb-master-0_az1 in case of multi-az.
        String nodeName = hostname.substring(ybIdx, hostname.length());
        nodeName = isMultiAz ? String.format("%s_%s", nodeName, azName) : nodeName;

        String podNamespace = podInfo.getMetadata().getNamespace();
        if (StringUtils.isBlank(podNamespace)) {
          throw new IllegalArgumentException(
              "metadata.namespace of pod " + hostname + " is empty. This shouldn't happen");
        }
        pod.put("namespace", podNamespace);

        pods.set(nodeName, pod);
      }
    }

    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          Set<NodeDetails> defaultNodes =
              universeDetails.getNodesInCluster(
                  taskParams().isReadOnlyCluster
                      ? universe.getUniverseDetails().getReadOnlyClusters().get(0).uuid
                      : universe.getUniverseDetails().getPrimaryCluster().uuid);
          NodeDetails defaultNode = defaultNodes.iterator().next();
          Set<NodeDetails> nodeDetailsSet = new HashSet<>();
          Iterator<Map.Entry<String, JsonNode>> iter = pods.fields();
          while (iter.hasNext()) {
            NodeDetails nodeDetail = defaultNode.clone();
            Map.Entry<String, JsonNode> pod = iter.next();
            String nodeName = pod.getKey();
            JsonNode podVals = pod.getValue();
            String hostname = podVals.get("hostname").asText();
            String namespace = podVals.get("namespace").asText();
            String helmFullNameWithSuffix = podVals.get("helmFullNameWithSuffix").asText();
            UUID azUUID = UUID.fromString(podVals.get("az_uuid").asText());
            String domain = azToDomain.get(azUUID);
            if (nodeName.contains("master")) {
              nodeDetail.isTserver = false;
              nodeDetail.isMaster = true;
              nodeDetail.cloudInfo.private_ip =
                  String.format(
                      "%s.%s%s.%s.%s",
                      hostname, helmFullNameWithSuffix, "yb-masters", namespace, domain);
            } else {
              nodeDetail.isMaster = false;
              nodeDetail.isTserver = true;
              nodeDetail.cloudInfo.private_ip =
                  String.format(
                      "%s.%s%s.%s.%s",
                      hostname, helmFullNameWithSuffix, "yb-tservers", namespace, domain);
            }
            if (isMultiAz) {
              nodeDetail.cloudInfo.az = podVals.get("az_name").asText();
              nodeDetail.cloudInfo.region = podVals.get("region_name").asText();
            }
            nodeDetail.azUuid = azUUID;
            nodeDetail.placementUuid = placementUuid;
            nodeDetail.state = NodeDetails.NodeState.Live;
            // If read cluster is deployed in same AZ as primary, node names will be same. To make
            // them unique, append readonly tag.
            nodeDetail.nodeName =
                taskParams().isReadOnlyCluster
                    ? String.format("%s%s", nodeName, Universe.READONLY)
                    : nodeName;
            nodeDetailsSet.add(nodeDetail);
          }
          // Remove existing cluster nodes and add nodeDetailsSet
          // Don't remove all as we might delete other cluster nodes.
          universeDetails.nodeDetailsSet.removeAll(defaultNodes);
          universeDetails.nodeDetailsSet.addAll(nodeDetailsSet);
          universe.setUniverseDetails(universeDetails);
        };
    saveUniverseDetails(updater);
  }

  // TODO: Remove this method as it is no longer needed. It does not
  // generate correct pod name with new naming style. The method which
  // was using this has stopped doing so as of
  // ea110f66098d2684863578cd2b730ec677e2de4e
  private String nodeNameToPodName(String nodeName, boolean isMaster) {
    Matcher matcher = nodeNamePattern.matcher(nodeName);
    if (!matcher.matches()) {
      throw new RuntimeException("Invalid nodeName : " + nodeName);
    }
    int nodeIdx = Integer.parseInt(matcher.group(1));
    return String.format("%s-%d", isMaster ? "yb-master" : "yb-tserver", nodeIdx - 1);
  }

  private String getPullSecret() {
    // Since the pull secret will always be the same across clusters,
    // it is always at the provider level.
    Provider provider = Provider.get(taskParams().providerUUID);
    if (provider != null) {
      Map<String, String> config = provider.getUnmaskedConfig();
      if (config.containsKey("KUBECONFIG_IMAGE_PULL_SECRET_NAME")) {
        return config.get("KUBECONFIG_PULL_SECRET");
      }
    }
    return null;
  }

  private int getNumNodes() {
    Provider provider = Provider.get(taskParams().providerUUID);
    if (provider != null) {
      Universe u = Universe.getOrBadRequest(taskParams().universeUUID);
      UniverseDefinitionTaskParams.UserIntent userIntent =
          u.getUniverseDetails().getPrimaryCluster().userIntent;
      return userIntent.numNodes;
    }
    return -1;
  }

  private String generateHelmOverride() {
    Map<String, Object> overrides = new HashMap<String, Object>();
    Yaml yaml = new Yaml();

    // TODO: decide if the user wants to expose all the services or just master.
    overrides = yaml.load(application.resourceAsStream("k8s-expose-all.yml"));

    Provider provider = Provider.get(taskParams().providerUUID);
    Map<String, String> config = provider.getUnmaskedConfig();
    Map<String, String> azConfig = new HashMap<String, String>();
    Map<String, String> regionConfig = new HashMap<String, String>();

    Universe u = Universe.getOrBadRequest(taskParams().universeUUID);
    UniverseDefinitionTaskParams.UserIntent userIntent =
        taskParams().isReadOnlyCluster
            ? u.getUniverseDetails().getReadOnlyClusters().get(0).userIntent
            : u.getUniverseDetails().getPrimaryCluster().userIntent;
    InstanceType instanceType =
        InstanceType.get(UUID.fromString(userIntent.provider), userIntent.instanceType);
    if (instanceType == null) {
      log.error(
          "Unable to fetch InstanceType for {}, {}",
          userIntent.providerType,
          userIntent.instanceType);
      throw new RuntimeException(
          "Unable to fetch InstanceType "
              + userIntent.providerType
              + ": "
              + userIntent.instanceType);
    }

    int numNodes = 0, replicationFactorZone = 0, replicationFactor = 0;
    String placementCloud = null;
    String placementRegion = null;
    String placementZone = null;

    // This is true always now.
    boolean isMultiAz = (taskParams().masterAddresses != null) ? true : false;

    PlacementInfo pi;
    if (taskParams().isReadOnlyCluster) {
      pi =
          isMultiAz
              ? taskParams().placementInfo
              : u.getUniverseDetails().getReadOnlyClusters().get(0).placementInfo;
    } else {
      pi =
          isMultiAz
              ? taskParams().placementInfo
              : u.getUniverseDetails().getPrimaryCluster().placementInfo;
    }

    // To maintain backward compatability with old helm charts,
    // for Read cluster we need to pass isMultiAz=true. (By default isMultiAz is set to true as
    // masterAddr is never null at this point).
    // For primary cluster we still have to pass isMultiAz=false for single AZ so that we can create
    // PDB.
    // isMultiAz actually tells us if what we are deploying is partial universe.
    // Ex: If we want to deploy a universe in multiple AZs, each AZ is partial deployment.
    // If we want to deploy universe with both primary and read replicas in only one AZ each cluster
    // deployment is partial universe.
    if (!taskParams().isReadOnlyCluster) {
      isMultiAz = PlacementInfoUtil.isMultiAZ(provider);
    }
    if (pi != null) {
      if (pi.cloudList.size() != 0) {
        PlacementInfo.PlacementCloud cloud = pi.cloudList.get(0);
        placementCloud = cloud.code;
        if (cloud.regionList.size() != 0) {
          PlacementInfo.PlacementRegion region = cloud.regionList.get(0);
          placementRegion = region.code;
          if (region.azList.size() != 0) {
            PlacementInfo.PlacementAZ zone = region.azList.get(0);
            // TODO: wtf, why do we have AZ name but not code at this level??
            placementZone = AvailabilityZone.get(zone.uuid).code;
            numNodes = zone.numNodesInAZ;
            replicationFactorZone = zone.replicationFactor;
            replicationFactor = userIntent.replicationFactor;
            azConfig = AvailabilityZone.get(zone.uuid).getUnmaskedConfig();
            regionConfig = Region.get(region.uuid).getUnmaskedConfig();
          }
        }
      }
    }

    Map<String, Object> storageOverrides =
        (HashMap) overrides.getOrDefault("storage", new HashMap<>());

    Map<String, Object> tserverDiskSpecs =
        (HashMap) storageOverrides.getOrDefault("tserver", new HashMap<>());
    Map<String, Object> masterDiskSpecs =
        (HashMap) storageOverrides.getOrDefault("master", new HashMap<>());
    // Override disk count and size for just the tserver pods according to user intent.
    if (userIntent.deviceInfo != null) {
      if (userIntent.deviceInfo.numVolumes != null) {
        tserverDiskSpecs.put("count", userIntent.deviceInfo.numVolumes);
      }
      if (userIntent.deviceInfo.volumeSize != null) {
        tserverDiskSpecs.put("size", String.format("%dGi", userIntent.deviceInfo.volumeSize));
      }
      // Storage class override applies to both tserver and master.
      if (userIntent.deviceInfo.storageClass != null) {
        tserverDiskSpecs.put("storageClass", userIntent.deviceInfo.storageClass);
        masterDiskSpecs.put("storageClass", userIntent.deviceInfo.storageClass);
      }
    }

    // Storage class needs to be updated if it is overriden in the zone config.
    if (azConfig.containsKey("STORAGE_CLASS")) {
      tserverDiskSpecs.put("storageClass", azConfig.get("STORAGE_CLASS"));
      masterDiskSpecs.put("storageClass", azConfig.get("STORAGE_CLASS"));
    }

    if (isMultiAz) {
      overrides.put("masterAddresses", taskParams().masterAddresses);
      // Don't want to use the AZ tag on minikube since there are no AZ tags
      if (!environment.isDev()) {
        overrides.put("AZ", placementZone);
      }
      overrides.put("isMultiAz", true);
      if (taskParams().isReadOnlyCluster) {
        overrides.put("replicas", ImmutableMap.of("tserver", numNodes, "master", 0));
      } else {
        overrides.put(
            "replicas",
            ImmutableMap.of(
                "tserver",
                numNodes,
                "master",
                replicationFactorZone,
                "totalMasters",
                replicationFactor));
      }
    } else {
      if (taskParams().isReadOnlyCluster) {
        overrides.put("replicas", ImmutableMap.of("tserver", numNodes, "master", 0));
      } else {
        overrides.put(
            "replicas", ImmutableMap.of("tserver", numNodes, "master", replicationFactor));
      }
    }

    if (!tserverDiskSpecs.isEmpty()) {
      storageOverrides.put("tserver", tserverDiskSpecs);
    }
    if (instanceType.getInstanceTypeCode().equals("cloud")) {
      masterDiskSpecs.put("size", String.format("%dGi", 3));
    }
    if (!masterDiskSpecs.isEmpty()) {
      storageOverrides.put("master", masterDiskSpecs);
    }

    // Override resource request and limit based on instance type.
    Map<String, Object> tserverResource = new HashMap<>();
    Map<String, Object> tserverLimit = new HashMap<>();
    Map<String, Object> masterResource = new HashMap<>();
    Map<String, Object> masterLimit = new HashMap<>();

    tserverResource.put("cpu", instanceType.numCores);
    tserverResource.put("memory", String.format("%.2fGi", instanceType.memSizeGB));
    tserverLimit.put("cpu", instanceType.numCores * burstVal);
    tserverLimit.put("memory", String.format("%.2fGi", instanceType.memSizeGB));

    // If the instance type is not xsmall or dev, we would bump the master resource.
    if (!instanceType.getInstanceTypeCode().equals("xsmall")
        && !instanceType.getInstanceTypeCode().equals("dev")) {
      masterResource.put("cpu", 2);
      masterResource.put("memory", "4Gi");
      masterLimit.put("cpu", 2 * burstVal);
      masterLimit.put("memory", "4Gi");
    }
    // For testing with multiple deployments locally.
    if (instanceType.getInstanceTypeCode().equals("dev")) {
      masterResource.put("cpu", 0.5);
      masterResource.put("memory", "0.5Gi");
      masterLimit.put("cpu", 0.5);
      masterLimit.put("memory", "0.5Gi");
    }
    // For cloud deployments, we want bigger bursts in CPU if available for better performance.
    // Memory should not be burstable as memory consumption above requests can lead to pods being
    // killed if the nodes is running out of resources.
    if (instanceType.getInstanceTypeCode().equals("cloud")) {
      tserverLimit.put("cpu", instanceType.numCores * 2);
      masterResource.put("cpu", 0.3);
      masterResource.put("memory", "1Gi");
      masterLimit.put("cpu", 0.6);
      masterLimit.put("memory", "1Gi");
    }

    Map<String, Object> resourceOverrides = new HashMap();
    if (!masterResource.isEmpty() && !masterLimit.isEmpty()) {
      resourceOverrides.put(
          "master",
          ImmutableMap.of(
              "requests", masterResource,
              "limits", masterLimit));
    }
    resourceOverrides.put(
        "tserver",
        ImmutableMap.of(
            "requests", tserverResource,
            "limits", tserverLimit));

    overrides.put("resource", resourceOverrides);

    Map<String, Object> imageInfo = new HashMap<>();
    // Override image tag based on ybsoftwareversion.
    String imageTag =
        taskParams().ybSoftwareVersion == null
            ? userIntent.ybSoftwareVersion
            : taskParams().ybSoftwareVersion;
    imageInfo.put("tag", imageTag);

    // Since the image registry will remain the same across differnet clusters,
    // it will always be at the provider level.
    if (config.containsKey("KUBECONFIG_IMAGE_REGISTRY")) {
      imageInfo.put("repository", config.get("KUBECONFIG_IMAGE_REGISTRY"));
    }
    if (config.containsKey("KUBECONFIG_IMAGE_PULL_SECRET_NAME")) {
      imageInfo.put("pullSecretName", config.get("KUBECONFIG_IMAGE_PULL_SECRET_NAME"));
    }
    overrides.put("Image", imageInfo);

    // Use primary cluster intent to read gflags, tls settings.
    UniverseDefinitionTaskParams.UserIntent primaryClusterIntent =
        u.getUniverseDetails().getPrimaryCluster().userIntent;

    if (u.getUniverseDetails().rootCA != null) {
      Map<String, Object> tlsInfo = new HashMap<>();
      tlsInfo.put("enabled", true);
      tlsInfo.put("nodeToNode", primaryClusterIntent.enableNodeToNodeEncrypt);
      tlsInfo.put("clientToServer", primaryClusterIntent.enableClientToNodeEncrypt);
      tlsInfo.put("insecure", u.getUniverseDetails().allowInsecure);

      String rootCert = CertificateHelper.getCertPEM(u.getUniverseDetails().rootCA);
      String rootKey = CertificateHelper.getKeyPEM(u.getUniverseDetails().rootCA);
      if (rootKey != null && !rootKey.isEmpty()) {
        Map<String, Object> rootCA = new HashMap<>();
        rootCA.put("cert", rootCert);
        rootCA.put("key", rootKey);
        tlsInfo.put("rootCA", rootCA);
      } else {
        // In case root cert key is null which will be the case with Hashicorp Vault certificates
        // Generate wildcard node cert and client cert and set them in override file
        CertificateInfo certInfo = CertificateInfo.get(u.getUniverseDetails().rootCA);
        CertificateProviderInterface certProvider =
            EncryptionInTransitUtil.getCertificateProviderInstance(
                certInfo, runtimeConfigFactory.staticApplicationConf());

        Map<String, Object> rootCA = new HashMap<>();
        rootCA.put("cert", rootCert);
        rootCA.put("key", null);
        tlsInfo.put("rootCA", rootCA);

        // Generate node cert from cert provider and set nodeCert param
        // As we are using same node cert for all nodes, set wildcard commonName
        String dnsWildCard1 = String.format("*.*.%s", taskParams().namespace);
        String dnsWildCard2 = dnsWildCard1 + ".svc.cluster.local";
        Map<String, Integer> subjectAltNames = new HashMap<>();
        subjectAltNames.put(dnsWildCard1, GeneralName.dNSName);
        subjectAltNames.put(dnsWildCard2, GeneralName.dNSName);
        CertificateDetails nodeCertDetails =
            certProvider.createCertificate(
                null, dnsWildCard2, null, null, null, null, subjectAltNames);
        Map<String, Object> nodeCert = new HashMap<>();
        nodeCert.put(
            "cert", Base64.getEncoder().encodeToString(nodeCertDetails.getCrt().getBytes()));
        nodeCert.put(
            "key", Base64.getEncoder().encodeToString(nodeCertDetails.getKey().getBytes()));
        tlsInfo.put("nodeCert", nodeCert);

        // Generate client cert from cert provider and set clientCert value
        CertificateDetails clientCertDetails =
            certProvider.createCertificate(null, "yugabyte", null, null, null, null, null);
        Map<String, Object> clientCert = new HashMap<>();
        clientCert.put(
            "cert", Base64.getEncoder().encodeToString(clientCertDetails.getCrt().getBytes()));
        clientCert.put(
            "key", Base64.getEncoder().encodeToString(clientCertDetails.getKey().getBytes()));
        tlsInfo.put("clientCert", clientCert);
      }

      overrides.put("tls", tlsInfo);
    }
    if (primaryClusterIntent.enableIPV6) {
      overrides.put("ip_version_support", "v6_only");
    }

    Map<String, Object> partition = new HashMap<>();
    partition.put("tserver", taskParams().tserverPartition);
    partition.put("master", taskParams().masterPartition);
    overrides.put("partition", partition);

    UUID placementUuid =
        taskParams().isReadOnlyCluster
            ? u.getUniverseDetails().getReadOnlyClusters().get(0).uuid
            : u.getUniverseDetails().getPrimaryCluster().uuid;
    Map<String, Object> gflagOverrides = new HashMap<>();
    // Go over master flags.
    Map<String, Object> masterOverrides = new HashMap<String, Object>(userIntent.masterGFlags);
    if (placementCloud != null && masterOverrides.get("placement_cloud") == null) {
      masterOverrides.put("placement_cloud", placementCloud);
    }
    if (placementRegion != null && masterOverrides.get("placement_region") == null) {
      masterOverrides.put("placement_region", placementRegion);
    }
    if (placementZone != null && masterOverrides.get("placement_zone") == null) {
      masterOverrides.put("placement_zone", placementZone);
    }
    if (placementUuid != null && masterOverrides.get("placement_uuid") == null) {
      masterOverrides.put("placement_uuid", placementUuid.toString());
    }
    if (u.getUniverseDetails().xClusterInfo.isSourceRootCertDirPathGflagConfigured()) {
      masterOverrides.put(
          XClusterConfigTaskBase.SOURCE_ROOT_CERTS_DIR_GFLAG,
          u.getUniverseDetails().xClusterInfo.sourceRootCertDirPath);
    }
    if (!masterOverrides.isEmpty()) {
      gflagOverrides.put("master", masterOverrides);
    }

    // Go over tserver flags.
    Map<String, Object> tserverOverrides =
        new HashMap<String, Object>(primaryClusterIntent.tserverGFlags);
    if (!primaryClusterIntent
        .enableYSQL) { // In the UI, we can choose not to show these entries for read replica.
      tserverOverrides.put("enable_ysql", "false");
    }
    if (!primaryClusterIntent.enableYCQL) {
      tserverOverrides.put("start_cql_proxy", "false");
    }
    if (primaryClusterIntent.enableYSQL && primaryClusterIntent.enableYSQLAuth) {
      tserverOverrides.put("ysql_enable_auth", "true");
      tserverOverrides.put("ysql_hba_conf_csv", "local all yugabyte trust");
    }
    if (primaryClusterIntent.enableYCQL && primaryClusterIntent.enableYCQLAuth) {
      tserverOverrides.put("use_cassandra_authentication", "true");
    }
    if (placementCloud != null && tserverOverrides.get("placement_cloud") == null) {
      tserverOverrides.put("placement_cloud", placementCloud);
    }
    if (placementRegion != null && tserverOverrides.get("placement_region") == null) {
      tserverOverrides.put("placement_region", placementRegion);
    }
    if (placementZone != null && tserverOverrides.get("placement_zone") == null) {
      tserverOverrides.put("placement_zone", placementZone);
    }
    if (placementUuid != null && tserverOverrides.get("placement_uuid") == null) {
      tserverOverrides.put("placement_uuid", placementUuid.toString());
    }
    if (u.getUniverseDetails().xClusterInfo.isSourceRootCertDirPathGflagConfigured()) {
      tserverOverrides.put(
          XClusterConfigTaskBase.SOURCE_ROOT_CERTS_DIR_GFLAG,
          u.getUniverseDetails().xClusterInfo.sourceRootCertDirPath);
    }
    if (!tserverOverrides.isEmpty()) {
      gflagOverrides.put("tserver", tserverOverrides);
    }

    if (!gflagOverrides.isEmpty()) {
      overrides.put("gflags", gflagOverrides);
    }

    if (azConfig.containsKey("KUBE_DOMAIN")) {
      overrides.put("domainName", azConfig.get("KUBE_DOMAIN"));
    }

    overrides.put("disableYsql", !primaryClusterIntent.enableYSQL);

    // If the value is anything else, that means the loadbalancer service by
    // default needed to be exposed.
    // NOTE: Will still be overriden from the provider level overrides.
    if (primaryClusterIntent.enableExposingService == ExposingServiceState.UNEXPOSED) {
      overrides.put("enableLoadBalancer", false);
    } else {
      // Even though the helm chart default is true, doing this from platform
      // just to make it explicit.
      overrides.put("enableLoadBalancer", true);
    }

    // For now the assumption is the all deployments will have the same kind of
    // loadbalancers, so the annotations will be at the provider level.
    // TODO (Arnav): Update this to use overrides created at the provider, region or
    // zone level.
    Map<String, Object> annotations = new HashMap<String, Object>();
    String overridesYAML = null;
    if (!azConfig.containsKey("OVERRIDES")) {
      if (!regionConfig.containsKey("OVERRIDES")) {
        if (config.containsKey("OVERRIDES")) {
          overridesYAML = config.get("OVERRIDES");
        }
      } else {
        overridesYAML = regionConfig.get("OVERRIDES");
      }
    } else {
      overridesYAML = azConfig.get("OVERRIDES");
    }

    if (overridesYAML != null) {
      annotations = (HashMap<String, Object>) yaml.load(overridesYAML);
      if (annotations != null) {
        mergeYaml(overrides, annotations);
      }
    }

    Map<String, String> universeConfig = u.getConfig();
    boolean helmLegacy =
        Universe.HelmLegacy.valueOf(universeConfig.get(Universe.HELM2_LEGACY))
            == Universe.HelmLegacy.V2TO3;

    if (helmLegacy) {
      overrides.put("helm2Legacy", helmLegacy);
      Map<String, String> serviceToIP = getClusterIpForLoadBalancer();
      ObjectMapper mapper = new ObjectMapper();
      ArrayList<Object> serviceEndpoints = (ArrayList) overrides.get("serviceEndpoints");
      for (Object serviceEndpoint : serviceEndpoints) {
        Map<String, Object> endpoint = mapper.convertValue(serviceEndpoint, Map.class);
        String endpointName = (String) endpoint.get("name");
        if (serviceToIP.containsKey(endpointName)) {
          // With the newNamingStyle, the serviceToIP map will have
          // service names containing helmFullNameWithSuffix in
          // them. NOT making any changes to this code, as we have
          // deprecated Helm 2. And the newNamingStyle will be used
          // for newly created universes using Helm 3.
          endpoint.put("clusterIP", serviceToIP.get(endpointName));
        }
      }
    }

    // TODO(bhavin192): we can save universeDetails at the top, we use
    // this call a couple of times throughout this method.
    if (u.getUniverseDetails().useNewHelmNamingStyle) {
      overrides.put("oldNamingStyle", false);
      overrides.put("fullnameOverride", taskParams().helmReleaseName);
    }

    try {
      Path tempFile = Files.createTempFile(taskParams().universeUUID.toString(), ".yml");
      BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile.toFile()));
      yaml.dump(overrides, bw);
      return tempFile.toAbsolutePath().toString();
    } catch (IOException e) {
      log.error(e.getMessage());
      throw new RuntimeException("Error writing Helm Override file!");
    }
  }

  // Recursively traverses the override map and updates or adds the
  // keys to source map.
  private void mergeYaml(Map<String, Object> source, Map<String, Object> override) {
    for (Entry<String, Object> entry : override.entrySet()) {
      String key = entry.getKey();
      if (!source.containsKey(key)) {
        source.put(key, override.get(key));
        continue;
      }
      if (!(override.get(key) instanceof Map) || !(source.get(key) instanceof Map)) {
        source.put(key, override.get(key));
        continue;
      }
      mergeYaml((Map<String, Object>) source.get(key), (Map<String, Object>) override.get(key));
    }
  }
}
