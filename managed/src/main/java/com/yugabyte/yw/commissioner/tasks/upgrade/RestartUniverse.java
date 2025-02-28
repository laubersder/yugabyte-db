// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.UpgradeTaskBase;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.NodeDetails.NodeState;
import java.util.List;
import javax.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;

public class RestartUniverse extends UpgradeTaskBase {

  @Inject
  protected RestartUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  @Override
  public SubTaskGroupType getTaskSubGroupType() {
    return SubTaskGroupType.StoppingNodeProcesses;
  }

  @Override
  public NodeState getNodeState() {
    return NodeState.Stopping;
  }

  @Override
  public void run() {
    runUpgrade(
        () -> {
          Pair<List<NodeDetails>, List<NodeDetails>> nodes = fetchNodes(taskParams().upgradeOption);
          // Verify the request params and fail if invalid
          taskParams().verifyParams(getUniverse());
          // Restart all nodes
          createRestartTasks(nodes, taskParams().upgradeOption, taskParams().ybcInstalled);
        });
  }
}
