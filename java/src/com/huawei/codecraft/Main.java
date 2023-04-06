package com.huawei.codecraft;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.util.*;

public class Main {
    private static final Scanner inStream = new Scanner(System.in);
    private static final PrintStream outStream = new PrintStream(new BufferedOutputStream(System.out));
    private static final int MAP_DATA_LENGTH = 100;
    private static final double MAP_WIDTH = 50;
    private static final int NOT_ON_PRODUCING = -1;
    private static final int NOT_NEAR_ANY_WORKBENCH = -1;
    private static int frameID;
    private static int curMoney;
    public static NumberStrategy STRATEGY = NumberStrategy.STRATEGY_MAP_3;
    private static final int WORKBENCH_TYPE_NUMBER = 9;
    private static List<WorkBenchState> workBenchStatesSequence  = null;
    private static final Map<Integer, List<WorkBenchState>> initWorkBenchStates = new HashMap<>(WORKBENCH_TYPE_NUMBER);
    private static final double FRAME_LENGTH = 0.02;
    private static final int RobotNumber = 4;
    private static final List<RobotState> robotStates = new ArrayList<>(RobotNumber);
    public static Deque<String> economicActions = new LinkedList<>();
    public static final Map<Integer, Action> movementActions = new HashMap<>();
    public static List<Task> optionalTaskQueue = new LinkedList<>();
    public static int robotTestNum = 0;
    public static final int TOTAL_FRAME = 9000;
    public static final int PRODUCTION_READY = 1;
    public static final Set<Integer> optionalProducerTypes = new HashSet<>();
    public static final int DO_NOT_CARRYING = 0;
    public static double redundancy;
    public static List<TaskChain> taskChainList = new ArrayList<>(2);
    public static double[][] avoidCollisionDistancesArray = new double[RobotNumber][RobotNumber];
    static List<List<int[]>> taskLists = new ArrayList<>(4);

    public static void main(String[] args) {
        schedule();
    }

    private static void schedule() {
        handleInitRobotStates();
        handleInitMap();
        if (STRATEGY == NumberStrategy.STRATEGY_MAP_1) {
            addTaskListsForStrategyEight();
            handleInitTaskChainsForStrategyEight(1);
        } else if (STRATEGY == NumberStrategy.STRATEGY_MAP_2) {
            addTaskListsForStrategyEight();
            handleInitTaskChainsForStrategy(2);
        } else if (STRATEGY == NumberStrategy.STRATEGY_MAP_3) {
            addTaskListsForStrategyNine();
            handleInitTaskChainsForStrategy(3);
        } else if (STRATEGY == NumberStrategy.STRATEGY_MAP_4) {
            addTaskListsForStrategyEight();
            handleInitTaskChainsForStrategy(3);
        }
        requestOK();
        while (inStream.hasNextLine()) {
            if (initWorkBenchStates.isEmpty()) {
                if (handleEachFrameInputData(initWorkBenchStates)) return;
                generateOptionalProducerTypes();
                generateOptionalTasks();
                addTaskForStrategy();
            } else {
                if (handleEachFrameInputData(null)) return;
            }
            outStream.printf("%d\n", frameID);
            executeTask();
            handleCrash();
            handleCorner();
            while (!economicActions.isEmpty()) {
                outStream.println(economicActions.poll());
            }
            for (Map.Entry<Integer, Action> actionEntry : movementActions.entrySet()) {
                Integer robotId = actionEntry.getKey();
                outStream.println(instruction(RobotMetadata.ROTATE, robotId, actionEntry.getValue().rotation));
                outStream.println(instruction(RobotMetadata.FORWARD, robotId, actionEntry.getValue().forward));
            }
            outStream.print("OK\n");
            outStream.flush();
        }
    }
    public static void generateOptionalProducerTypes() {
        // get all optional producer types
        for (int type = 0; type < WorkbenchMetadata.materialInfoNodes.size(); type++) {
            MaterialInfoNode node = WorkbenchMetadata.materialInfoNodes.get(type);
            if (node == null || node.parentTypes == null /*8, 9*/ || initWorkBenchStates.get(type) == null || initWorkBenchStates.get(type).isEmpty()) {
                continue;
            }
            for (int parentType : node.parentTypes) {
                // if it has consumer, can sell
                if (initWorkBenchStates.get(parentType) != null && !initWorkBenchStates.get(parentType).isEmpty()) {
                    optionalProducerTypes.add(type);
                    break; // one is enough
                }
            }
        }
    }
    public static void generateOptionalTasks() {
        for (Integer fromWorkBenchType : optionalProducerTypes) {
            List<WorkBenchState> fromWorkBenches = initWorkBenchStates.get(fromWorkBenchType);
            List<WorkBenchState> toWorkBenches = new LinkedList<>();
            for (int toWorkBenchType : WorkbenchMetadata.materialInfoNodes.get(fromWorkBenchType).parentTypes) {
                if (initWorkBenchStates.get(toWorkBenchType) == null) {
                    continue;
                }
                toWorkBenches.addAll(initWorkBenchStates.get(toWorkBenchType));
            }
            for (WorkBenchState fromWorkBench : fromWorkBenches) {
                for (WorkBenchState toWorkBench : toWorkBenches) {
                    double distance = calculateDistance(fromWorkBench.x, fromWorkBench.y, toWorkBench.x, toWorkBench.y);
                    Task task = new Task(fromWorkBench, toWorkBench, distance);
                    optionalTaskQueue.add(task);
                }
            }
        }
    }

    public static double calculateLongTermValue(Task task, double taskProfit, double timeCost) {
        if (TOTAL_FRAME - frameID < WorkbenchMetadata.frameCounts[task.fromWorkbench.type] + timeCost) {
            return 0;
        }
        double profitRate = 0;
        MaterialInfoNode node = WorkbenchMetadata.materialInfoNodes.get(task.fromWorkbench.type);
        if (node.childrenTypes == null) { // 1, 2, 3
            profitRate += taskProfit / timeCost;
        } else if (workBenchStatesSequence.get(task.fromWorkbench.index).productStatus == PRODUCTION_READY) { // avoid coming in by remaining time
            boolean isMaterialCompleted = true;
            for (int childrenType : node.childrenTypes) { // 4, 5, 6, 7
                // if material is full, take this task can let fromWorkbench production begin
                if (calculateRawMaterialsIsEmpty(task.fromWorkbench.rawMaterialsStatus, childrenType)) {
                    isMaterialCompleted = false;
                    break;
                }
            }
            if (isMaterialCompleted) {
                profitRate += taskProfit / timeCost;
            }
        }
        if (TOTAL_FRAME - frameID < WorkbenchMetadata.frameCounts[task.toWorkbench.type] + timeCost) {
            return profitRate;
        }
        node = WorkbenchMetadata.materialInfoNodes.get(task.toWorkbench.type);
        boolean isMaterialCompleted = true;
        for (int childrenType : node.childrenTypes) { // 4, 5, 6, 7
            // if other material is full, take this task can let toWorkbench production begin
            if (childrenType == task.fromWorkbench.type) {
                continue;
            }
            if (calculateRawMaterialsIsEmpty(task.fromWorkbench.rawMaterialsStatus, childrenType)) {
                isMaterialCompleted = false;
                break;
            }
        }
        if (isMaterialCompleted) {
            Task bestTask = null;
            double maxProfitRate = 0;
            for (Task optionalTask : optionalTaskQueue) {
                if (optionalTask.fromWorkbench.index == task.toWorkbench.index) {
                    bestTask = optionalTask;
                }
            }
            profitRate += bestTask == null ? 0 : calculateTaskProfit(bestTask, bestTask.distanceFromToTo, 0) / calculateTaskTimeCost(bestTask.distanceFromToTo, 0);
        }
        return profitRate;
    }

    public static double calculateValueCoefficient(double x, double maxX, double minRate) {
        if (x < maxX) {
            return (1 - Math.sqrt(1 - Math.pow((1 - x / maxX), 2))) * (1 - minRate) + minRate;
        } else {
            return minRate;
        }
    }
    public static double calculateTimeValueCoefficient(double distance, double rotation) {
        redundancy = RobotMetadata.FORWARD_MAX_VELOCITY / (RobotMetadata.F / RobotMetadata.M_CARRYING) * 1.5; // accelerate time
        return calculateValueCoefficient(((distance / RobotMetadata.FORWARD_MAX_VELOCITY + redundancy) / FRAME_LENGTH) + (rotation / RobotMetadata.ROTATE_MAX_VELOCITY / FRAME_LENGTH), 9000, 0.8);
    }
    private static double calculateTaskProfit(Task task, double distance, double rotation) {
        return WorkbenchMetadata.sellingPrices[task.fromWorkbench.type] * calculateTimeValueCoefficient(distance, rotation) - WorkbenchMetadata.purchasePrices[task.fromWorkbench.type];
    }
    private static double calculateTaskTimeCost(double distance, double rotation) {
        redundancy = RobotMetadata.FORWARD_MAX_VELOCITY / (RobotMetadata.F / RobotMetadata.M_CARRYING) * 1.5; // accelerate time
        return distance / RobotMetadata.FORWARD_MAX_VELOCITY / FRAME_LENGTH + rotation / RobotMetadata.ROTATE_MAX_VELOCITY / FRAME_LENGTH + redundancy;
    }
    public static Deque<Task> generateTaskQueue(RobotState robotState, List<Task> optionalTaskQueue) {
        Comparator<Task> comparator = new Comparator<Task>() {
            @Override
            public int compare(Task taskA, Task taskB) {
                redundancy = 2.5;
                if (taskA.profitTimeCostRate == null) {
                    double distanceToFrom = calculateDistance(robotState.x, robotState.y, taskA.fromWorkbench.x, taskA.fromWorkbench.y);
                    double distanceA = taskA.distanceFromToTo + distanceToFrom * redundancy;
                    double toFromWorkbenchRotationA = calculateAngleInRadians(robotState.x, robotState.y, taskA.fromWorkbench.x, taskA.fromWorkbench.y);
                    double rotation = calculateAngleInRadians(taskA.fromWorkbench.x, taskA.fromWorkbench.y, taskA.toWorkbench.x, taskA.toWorkbench.y);
                    double rotationA = Math.abs(toFromWorkbenchRotationA - robotState.orientation) + Math.abs(toFromWorkbenchRotationA - rotation);
                    double taskProfitA = calculateTaskProfit(taskA, taskA.distanceFromToTo, Math.abs(toFromWorkbenchRotationA - rotation));
                    double timeCostA = calculateTaskTimeCost(distanceA, rotationA);
                    taskA.rotation = rotation;
                    taskA.profit = taskProfitA;
                    taskA.timeCost = timeCostA;
                    taskA.profitTimeCostRate = taskProfitA / timeCostA;
                }
                if (taskB.profitTimeCostRate == null) {
                    double distanceToFrom = calculateDistance(robotState.x, robotState.y, taskB.fromWorkbench.x, taskB.fromWorkbench.y);
                    double distanceB = taskB.distanceFromToTo + distanceToFrom * redundancy;
                    double toFromWorkbenchRotationB = calculateAngleInRadians(robotState.x, robotState.y, taskB.fromWorkbench.x, taskB.fromWorkbench.y);
                    double rotation = calculateAngleInRadians(taskB.fromWorkbench.x, taskB.fromWorkbench.y, taskB.toWorkbench.x, taskB.toWorkbench.y);
                    double rotationB = Math.abs(toFromWorkbenchRotationB - robotState.orientation) + Math.abs(toFromWorkbenchRotationB - rotation);
                    double taskProfitB = calculateTaskProfit(taskB, taskB.distanceFromToTo, Math.abs(toFromWorkbenchRotationB - rotation));
                    double timeCostB = calculateTaskTimeCost(distanceB, rotationB);
                    taskB.rotation = rotation;
                    taskB.profit = taskProfitB;
                    taskB.timeCost = timeCostB;
                    taskB.profitTimeCostRate = taskProfitB / timeCostB;
                }
                if (STRATEGY == NumberStrategy.STRATEGY_MAP_2) {
                    if (taskA.fromWorkbench.type == 6) {
                        return -1;
                    } else if (taskB.fromWorkbench.type == 6) {
                        return 1;
                    }
                }
                if (robotState.taskChain != null && !robotState.taskChain.taskQueueFromStrategy.isEmpty()) {
                    Task targetTask = robotState.taskChain.taskQueueFromStrategy.get(robotState.taskChain.taskQueueFromStrategy.size() - 1);
                    if (taskA.fromWorkbench.type != targetTask.fromWorkbench.type) {
                        boolean materialAIsEmpty = calculateRawMaterialsIsEmpty(workBenchStatesSequence.get(targetTask.fromWorkbench.index).rawMaterialsStatus, taskA.toWorkbench.type);
                        boolean materialBIsEmpty = calculateRawMaterialsIsEmpty(workBenchStatesSequence.get(targetTask.fromWorkbench.index).rawMaterialsStatus, taskB.toWorkbench.type);
                        if (materialAIsEmpty && !materialBIsEmpty) {
                            return -1;
                        } else if (!materialAIsEmpty && materialBIsEmpty) {
                            return 1;
                        }
                    }
                }
                return Double.compare(taskB.profitTimeCostRate, taskA.profitTimeCostRate);
            }
        };

        PriorityQueue<Task> queue = new PriorityQueue<>(comparator);
        for (Task task : optionalTaskQueue) {
            double curRobotDistanceToFromWorkbench = calculateDistance(robotState.x, robotState.y, task.fromWorkbench.x, task.fromWorkbench.y);
            double rotation = Math.abs(robotState.orientation - calculateAngleInRadians(robotState.x, robotState.y, task.fromWorkbench.x, task.fromWorkbench.y));
            redundancy = (rotation / RobotMetadata.ROTATE_MAX_VELOCITY / FRAME_LENGTH) / 3;
            if ((workBenchStatesSequence.get(task.fromWorkbench.index).productStatus == PRODUCTION_READY
                    || (workBenchStatesSequence.get(task.fromWorkbench.index).remainingFrameCount != NOT_ON_PRODUCING
                    && (workBenchStatesSequence.get(task.fromWorkbench.index).remainingFrameCount + redundancy) * FRAME_LENGTH * RobotMetadata.FORWARD_MAX_VELOCITY < curRobotDistanceToFromWorkbench)
                )
                    && calculateRawMaterialsIsEmpty(workBenchStatesSequence.get(task.toWorkbench.index).rawMaterialsStatus, task.fromWorkbench.type)
                    && (!task.toWorkbench.readyToGetMaterialTypes.contains(task.fromWorkbench.type) && !task.fromWorkbench.readyToBeTaken)) {
                double distance = task.distanceFromToTo + curRobotDistanceToFromWorkbench;
                redundancy = Math.abs(robotState.orientation - calculateAngleInRadians(task.fromWorkbench.x, task.fromWorkbench.y, task.toWorkbench.x, task.toWorkbench.y)) / RobotMetadata.ROTATE_MAX_VELOCITY / FRAME_LENGTH; // rotate time
                redundancy += RobotMetadata.FORWARD_MAX_VELOCITY / (RobotMetadata.F / RobotMetadata.M_CARRYING); // accelerate time
                redundancy += 100;
                if ((TOTAL_FRAME - frameID - redundancy) * FRAME_LENGTH * RobotMetadata.FORWARD_MAX_VELOCITY > distance) {
                    task.profitTimeCostRate = null;
                    queue.add(task);
                }
            }
        }
        return new LinkedList<>(queue);
    }


    public static void letOtherRobotExecute(Deque<Task> taskQueue, RobotState curRobotState) {
        if (taskQueue.size() <= 1) {
            return;
        }
        Task task = taskQueue.peek();
        double curDistance = calculateDistance(curRobotState.x, curRobotState.y, task.fromWorkbench.x, task.fromWorkbench.y);
        for (RobotState robotState : robotStates) {
            if (robotState == curRobotState) {
                continue;
            }
            double otherDistance;
            if (robotState.getTask() == null) {
                otherDistance = calculateDistance(robotState.x, robotState.y, task.fromWorkbench.x, task.fromWorkbench.y);
            } else {
                otherDistance = calculateDistance(robotState.getTask().toWorkbench.x, robotState.getTask().toWorkbench.y, task.fromWorkbench.x, task.fromWorkbench.y);
            }
            redundancy = 2;
            if (otherDistance + redundancy < curDistance) {
                taskQueue.poll();
                return;
            }
        }
    }

    public static void letOtherRobotExecuteCompactChangeConditionEdition(Deque<Task> taskQueue, RobotState curRobotState) {
        if (taskQueue.size() <= 1) {
            return;
        }
        Task task = taskQueue.peek();
        double curDistance = calculateDistance(curRobotState.x, curRobotState.y, task.fromWorkbench.x, task.fromWorkbench.y);
        for (RobotState robotState : robotStates) {
            if (robotState == curRobotState) {
                continue;
            }
            double otherDistance = Double.MAX_VALUE;
            if (robotState.getTask() == null) {
                otherDistance = calculateDistance(robotState.x, robotState.y, task.fromWorkbench.x, task.fromWorkbench.y);
            } else if (robotState.getTask().toWorkbench == task.fromWorkbench){
                otherDistance = 0;
            }
            redundancy = 2;
            if (otherDistance + redundancy < curDistance) {
                taskQueue.poll();
                return;
            }
        }
    }

    public static void changeTask(Deque<Task> taskQueue, RobotState robotState, List<Task> optionalTaskQueue) {
        if (taskQueue.size() <= 1) {
            return;
        }
        Task task = taskQueue.peek();
        int[] childrenTypes = WorkbenchMetadata.materialInfoNodes.get(task.toWorkbench.type).childrenTypes;
        HashSet<Integer> childrenHashSet = new HashSet<>();
        if (childrenTypes != null) {
            for (int childrenType : childrenTypes) {
                if (calculateRawMaterialsIsEmpty(workBenchStatesSequence.get(task.fromWorkbench.index).rawMaterialsStatus, childrenType)) {
                    childrenHashSet.add(childrenType);
                }
            }
        }
        if (robotState.workbenchId != NOT_NEAR_ANY_WORKBENCH) {
            WorkBenchState workBenchState = workBenchStatesSequence.get(robotState.workbenchId);
            int[] parentTypes = WorkbenchMetadata.materialInfoNodes.get(workBenchState.type).parentTypes;
            if (parentTypes != null) {
                for (int parentType : parentTypes) {
                    if (task.fromWorkbench.type == parentType && workBenchState.productStatus == PRODUCTION_READY
                            && (calculateRawMaterialsIsEmpty(workBenchStatesSequence.get(task.fromWorkbench.index).rawMaterialsStatus, workBenchState.type)
                            || (childrenTypes != null && childrenHashSet.isEmpty()))) {
                        for (Task optionalTask : optionalTaskQueue) {
                            if (optionalTask.fromWorkbench.index == workBenchState.index && optionalTask.toWorkbench.index == task.fromWorkbench.index
                                && !optionalTask.toWorkbench.readyToGetMaterialTypes.contains(optionalTask.fromWorkbench.type)
                                    && !optionalTask.fromWorkbench.readyToBeTaken) {
                                taskQueue.addFirst(optionalTask);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
    // add task
    public static void addTaskForMission(int fromWorkbenchType, int toWorkbenchType, TaskChain taskChain) {
        List<WorkBenchState> producers = new LinkedList<>();
        if (initWorkBenchStates.get(fromWorkbenchType) != null) {
            for (WorkBenchState toWorkBenchState : initWorkBenchStates.get(fromWorkbenchType)) {
                if (toWorkBenchState.isNotAssignedToTaskChain) {
                    producers.add(toWorkBenchState);
                }
            }
            if (producers.isEmpty()) {
                producers = initWorkBenchStates.get(fromWorkbenchType);
                taskChain.restrictiveConsumerWorkbenchType.add(fromWorkbenchType);
            }
        }

        List<WorkBenchState> consumers = new LinkedList<>();
        WorkBenchState toWorkbench = taskChain.usingFromWorkbenchStates.get(toWorkbenchType);
        if (toWorkbench != null) {
            consumers.add(toWorkbench);
        } else {
            if (initWorkBenchStates.get(toWorkbenchType) != null) {
                for (WorkBenchState toWorkBenchState : initWorkBenchStates.get(toWorkbenchType)) {
                    if (toWorkBenchState.isNotAssignedToTaskChain) {
                        consumers.add(toWorkBenchState);
                    }
                }
                if (consumers.isEmpty()) {
                    consumers = initWorkBenchStates.get(toWorkbenchType);
                }
            }
        }


        int nearest;
        double nearestDistance;
        double distance;
        for (WorkBenchState producer : producers) {
            nearest = 0;
            nearestDistance = Double.MAX_VALUE;
            for (int i = 0; i < consumers.size(); i++) {
                distance = calculateDistance(producer.x, producer.y, consumers.get(i).x, consumers.get(i).y);
                nearest = nearestDistance <= distance ? nearest : i;
                nearestDistance = Math.min(nearestDistance, distance);
            }
            producer.distance = nearestDistance;
            producer.bindingConsumerWorkbench = consumers.get(nearest);
        }
        // Sorted by distance to bindingConsumerWorkbench
        PriorityQueue<WorkBenchState> queue = new PriorityQueue<>((producerA, producerB) -> (int) (producerA.distance - producerB.distance));
        queue.addAll(producers);

        // Generate task
        if (!queue.isEmpty()) {
            WorkBenchState producer = queue.poll();
            // if toWorkbench is null, means the toWorkbench is not pointed
            Task newTask = toWorkbench == null ? new Task(producer, producer.bindingConsumerWorkbench) : new Task(producer, toWorkbench);
            newTask.distanceFromToTo = calculateDistance(newTask.fromWorkbench.x, newTask.fromWorkbench.y, newTask.toWorkbench.x, newTask.toWorkbench.y);
            taskChain.addTask(newTask);
        }
    }
    private static void handleInitTaskChainsForStrategy(int taskChainNum) {
        for (int i = 0; i < taskChainNum; i++) {
            taskChainList.add(new TaskChain());
        }
        for (int robotId = 0; robotId < RobotNumber; robotId++) {
            robotStates.get(robotId).taskChain = taskChainList.get(robotId % taskChainNum);
        }
    }

    public static void addTaskListsForStrategyNine() {
        List<int[]> taskList1 = new LinkedList<>();
        taskList1.add(new int[]{6, 9});
        taskList1.add(new int[]{2, 6});
        taskList1.add(new int[]{3, 6});
        taskList1.add(new int[]{6, 9});
        taskList1.add(new int[]{2, 6});
        taskList1.add(new int[]{3, 6});

        List<int[]> taskList2 = new LinkedList<>();
        taskList2.add(new int[]{5, 9});
        taskList2.add(new int[]{1, 5});
        taskList2.add(new int[]{3, 5});
        taskList2.add(new int[]{5, 9});
        taskList2.add(new int[]{1, 5});
        taskList2.add(new int[]{3, 5});

        List<int[]> taskList3 = new LinkedList<>();
        taskList3.add(new int[]{4, 9});
        taskList3.add(new int[]{1, 4});
        taskList3.add(new int[]{2, 4});
        taskList3.add(new int[]{4, 9});
        taskList3.add(new int[]{1, 4});
        taskList3.add(new int[]{2, 4});

        taskLists.add(taskList1);
        taskLists.add(taskList2);
        taskLists.add(taskList3);
    }

    public static void addTaskListsForStrategyEight() {
        List<int[]> taskList1 = new LinkedList<>();
        taskList1.add(new int[]{7, 8});
        taskList1.add(new int[]{6, 7});
        taskList1.add(new int[]{5, 7});
        taskList1.add(new int[]{4, 7});
        taskList1.add(new int[]{3, 6});
        taskList1.add(new int[]{2, 6});
        taskList1.add(new int[]{1, 5});
        taskList1.add(new int[]{3, 5});
        taskList1.add(new int[]{1, 4});
        taskList1.add(new int[]{2, 4});

        List<int[]> taskList2 = new LinkedList<>();
        taskList2.add(new int[]{7, 8});
        taskList2.add(new int[]{6, 7});
        taskList2.add(new int[]{5, 7});
        taskList2.add(new int[]{4, 7});

        taskList2.add(new int[]{3, 6});
        taskList2.add(new int[]{2, 6});
        taskList2.add(new int[]{1, 5});
        taskList2.add(new int[]{3, 5});
        taskList2.add(new int[]{1, 4});
        taskList2.add(new int[]{2, 4});

        taskLists.add(taskList1);
        taskLists.add(taskList2);
    }
    public static void addTaskForStrategy() {
        for (int i = 0; i < taskChainList.size(); i++) {
            TaskChain taskChain = taskChainList.get(i);
            for (int[] ints : taskLists.get(i % taskLists.size())) {
                int fromWorkbenchType = ints[0];
                int toWorkbenchType = ints[1];
                addTaskForMission(fromWorkbenchType, toWorkbenchType, taskChain);
            }
        }
    }
    public static void executeTask() {
        for (RobotState robotState : robotStates) {
            if (robotState.getTask() == null) {
                if (STRATEGY == NumberStrategy.STRATEGY_MAP_1) {
                    Deque<Task> taskQueue = generateTaskQueue(robotState, robotState.taskChain.taskQueueFromStrategy);
                    letOtherRobotExecuteCompactChangeConditionEdition(taskQueue, robotState);
                    changeTask(taskQueue, robotState, robotState.taskChain.taskQueueFromStrategy);
                    robotState.setTask(taskQueue.poll());
                } else if (STRATEGY == NumberStrategy.STRATEGY_MAP_2) {
                    Deque<Task> taskQueue = generateTaskQueue(robotState, robotState.taskChain.taskQueueFromStrategy);
                    if (taskQueue.isEmpty()) {
                        taskQueue = generateTaskQueue(robotState, optionalTaskQueue);
                    }
                    letOtherRobotExecute(taskQueue, robotState);
                    changeTask(taskQueue, robotState, robotState.taskChain.taskQueueFromStrategy);
                    robotState.setTask(taskQueue.poll());
                } else if (STRATEGY == NumberStrategy.STRATEGY_MAP_3) {
                    Deque<Task> taskQueue = generateTaskQueue(robotState, robotState.taskChain.taskQueueFromStrategy);
                    letOtherRobotExecuteCompactChangeConditionEdition(taskQueue, robotState);
                    changeTask(taskQueue, robotState, robotState.taskChain.taskQueueFromStrategy);
                    robotState.setTask(taskQueue.poll());
                } else if (STRATEGY == NumberStrategy.STRATEGY_MAP_4) {
                    Deque<Task> taskQueue = generateTaskQueue(robotState, robotState.taskChain.taskQueueFromStrategy);
                    letOtherRobotExecuteCompactChangeConditionEdition(taskQueue, robotState);
                    changeTask(taskQueue, robotState, robotState.taskChain.taskQueueFromStrategy);
                    robotState.setTask(taskQueue.poll());
                }
            }
        }

        // generate instructions
        for (int robotId = robotTestNum; robotId < RobotNumber; robotId++) {
            RobotState robotState = robotStates.get(robotId);
            if (robotState.getTask() == null) {
                continue;
            }
            WorkBenchState destination;
            if (robotState.economicAction == EconomicAction.PURCHASE) {
                destination = robotState.getTask().fromWorkbench;
            } else {
                destination = robotState.getTask().toWorkbench;
            }

            // calculate forward
            double distance = calculateDistance(robotState.x, robotState.y, destination.x, destination.y);
            redundancy = 0.05;
            double forward = Math.min(RobotMetadata.FORWARD_MAX_VELOCITY, (distance - RobotMetadata.economicActionAllowRange + redundancy) / FRAME_LENGTH);
            // calculate rotate
            double radian = calculateAngleInRadians(robotState.x, robotState.y, destination.x, destination.y) - robotState.orientation;
            double TIME = FRAME_LENGTH * 4;
            double impetusRotation = robotState.angular_velocity * TIME + 0.5 *  RobotMetadata.MAXIMUM_TORQUE_F / (robotState.carryingItemType == 0 ? RobotMetadata.M : RobotMetadata.M_CARRYING) * Math.pow(TIME, 2);
            double rotation = (radian - impetusRotation) / FRAME_LENGTH;
            if (robotState.economicAction == EconomicAction.PURCHASE && robotState.getTask() != null
                    && distance < RobotMetadata.economicActionAllowRange) {
                radian = calculateAngleInRadians(robotState.getTask().fromWorkbench.x, robotState.getTask().fromWorkbench.y, robotState.getTask().toWorkbench.x, robotState.getTask().toWorkbench.y) - robotState.orientation;
                rotation = (radian - impetusRotation) / FRAME_LENGTH;
            }
             if (distance < RobotMetadata.economicActionAllowRange && robotState.workbenchId != -1) {
                WorkBenchState workBenchState = workBenchStatesSequence.get(robotState.workbenchId);
                if (robotState.economicAction == EconomicAction.PURCHASE && workBenchState.type == robotState.getTask().fromWorkbench.type && curMoney > WorkbenchMetadata.purchasePrices[robotState.getTask().fromWorkbench.type] && workBenchState.productStatus == PRODUCTION_READY
                        && (Math.abs(radian) < Math.PI / 2)) {
                    economicActions.add(instruction(RobotMetadata.BUT, robotId));
                    robotState.getTask().fromWorkbench.readyToBeTaken = false;
                    robotState.economicAction = EconomicAction.SELL;
                } else if (robotState.economicAction == EconomicAction.SELL && workBenchState.type == robotState.getTask().toWorkbench.type && calculateRawMaterialsIsEmpty(workBenchState.rawMaterialsStatus, robotState.getTask().fromWorkbench.type)) {
                    economicActions.add(instruction(RobotMetadata.SELL, robotId));
                    robotState.getTask().toWorkbench.readyToGetMaterialTypes.remove(robotState.getTask().fromWorkbench.type);
                    // If the current workbench has something produced, take it away
                    robotState.economicAction = EconomicAction.PURCHASE;
                    robotState.lastBeenToWorkbench = robotState.getTask().toWorkbench;
                    robotState.setTask(null);
                }
            }else {
                if (Math.abs(radian) <= Math.PI / 4) {
                    forward = distance / FRAME_LENGTH;
                } else {
                    forward = -(distance / FRAME_LENGTH);
                }
            }
            movementActions.put(robotId, new Action(forward, rotation));
        }
    }

    public static boolean testIfWillCrash(RobotState robotStateA, RobotState robotStateB, int frameRange) {
        boolean isCrash = false;
        redundancy = RobotMetadata.R / 2;
        double distance = redundancy + robotStateA.carryingItemType == DO_NOT_CARRYING ? RobotMetadata.R : RobotMetadata.R_CARRYING + robotStateB.carryingItemType == DO_NOT_CARRYING ? RobotMetadata.R : RobotMetadata.R_CARRYING;
        for (int i = 0; i <= frameRange; i++) {
            if (calculateDistance(robotStateA.x + robotStateA.linear_velocity_x * i, robotStateA.y + robotStateA.linear_velocity_y * i,
                    robotStateB.x + robotStateB.linear_velocity_x * i, robotStateB.y + robotStateB.linear_velocity_y * i) >= distance) {
                isCrash = true;
                break;
            }
        }

        return isCrash;
    }

    public static double calculateVelocity(double velocityX, double velocityY) {
        return Math.sqrt(velocityX * velocityX + velocityY * velocityY);
    }
    public static void handleCrash() {
        for (int robotIdA = 0; robotIdA < RobotNumber; robotIdA++) {
            for (int robotIdB = robotIdA + 1; robotIdB < RobotNumber; robotIdB++) {
                RobotState robotStateA = robotStates.get(robotIdA);
                RobotState robotStateB = robotStates.get(robotIdB);
                RobotMetadata.PREV_FRAME = 20;
                if (testIfWillCrash(robotStateA, robotStateB, RobotMetadata.PREV_FRAME) && !(robotStateA.carryingItemType == DO_NOT_CARRYING && robotStateB.carryingItemType == DO_NOT_CARRYING)) {
                    double distance = calculateDistance(robotStateA.x, robotStateA.y, robotStateB.x, robotStateB.y);
                    RobotMetadata.PREV_FRAME = (robotStateA.carryingItemType == DO_NOT_CARRYING || robotStateB.carryingItemType == DO_NOT_CARRYING) ? 10 : 15;
                    redundancy = FRAME_LENGTH * calculateVelocity(robotStateA.linear_velocity_x - robotStateB.linear_velocity_x, robotStateA.linear_velocity_y - robotStateB.linear_velocity_y);
                    double avoidCollisionDistance = robotStateA.carryingItemType == DO_NOT_CARRYING ? (RobotMetadata.R + RobotMetadata.X) : (RobotMetadata.R_CARRYING + RobotMetadata.X_CARRYING) +
                            robotStateB.carryingItemType == DO_NOT_CARRYING ? (RobotMetadata.R + RobotMetadata.X) : (RobotMetadata.R_CARRYING + RobotMetadata.X_CARRYING) + redundancy;
                    avoidCollisionDistancesArray[robotIdA][robotIdB] = avoidCollisionDistance;
                    // Collision occurs
                    if (distance < avoidCollisionDistance) {
                        if (inWhichCorner(robotStateA, avoidCollisionDistance) != CORNER.NO_CORNER && inWhichCorner(robotStateB, avoidCollisionDistance) != CORNER.NO_CORNER) {
                        //if (inWhichCorner(robotStateA, avoidCollisionDistance) == CORNER.TOP_LEFT && inWhichCorner(robotStateB, avoidCollisionDistance) == CORNER.TOP_LEFT) {
                            if (robotStateA.corner == robotStateB.corner) {
                                // not in corner yield
                                if (robotStateA.distanceToCorner >= robotStateB.distanceToCorner) {
                                    handleInCornerMovement(robotIdA, robotStateA, robotStateB);
                                } else {
                                    handleInCornerMovement(robotIdB, robotStateB, robotStateA);
                                }
                            }
                        } else {
                            // not carrying yield
                            if (robotStateA.carryingItemType == DO_NOT_CARRYING) {
                                handleCrashMovement(robotIdA, robotStateA, robotStateB);
                            } else if (robotStateB.carryingItemType == DO_NOT_CARRYING) {
                                handleCrashMovement(robotIdB, robotStateB, robotStateA);
                            } else {
                                if (robotStateA.carryingItemType <= robotStateB.carryingItemType) {
                                    handleCrashMovement(robotIdA, robotStateA, robotStateB);
                                } else {
                                    handleCrashMovement(robotIdB, robotStateB, robotStateA);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    public static void handleCorner() {
        for (int robotIdA = 0; robotIdA < RobotNumber; robotIdA++) {
            RobotState robotState = robotStates.get(robotIdA);
            redundancy = 0.1;
            if (robotState.x - RobotMetadata.R < redundancy && robotState.y - RobotMetadata.R < redundancy) {
                movementActions.put(robotIdA, new Action(-RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY));
            }
        }
    }
    private static void handleInCornerMovement(int robotIdA, RobotState robotStateA, RobotState robotStateB) {
        double orientationA = robotStateA.orientation;
        double relativeDirectionRadiusA = calculateAngleInRadians(robotStateA.x, robotStateA.y, robotStateB.x, robotStateB.y); // the relativeDirectionRadius from A to B
        double relativeOrientationA = orientationA - relativeDirectionRadiusA;
        if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI / 2) { // right top
            movementActions.put(robotIdA, new Action(-RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY));
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI / 2) {
            movementActions.put(robotIdA, new Action(-RobotMetadata.FORWARD_MAX_VELOCITY, -RobotMetadata.ROTATE_MAX_VELOCITY));
        } else {
            movementActions.put(robotIdA, new Action(-RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY));
        }
    }


    private static CORNER inWhichCorner(RobotState robotState, double range) {
        double distanceToLeftBottom = calculateDistance(robotState.x, robotState.y, 0, 0);
        double distanceToRightBottom = calculateDistance(robotState.x, robotState.y, 50, 0);
        double distanceToTopLeft = calculateDistance(robotState.x, robotState.y, 0, 50);
        double distanceToTopRight = calculateDistance(robotState.x, robotState.y, 50, 50);
        redundancy = 1;
        range += redundancy;
        if (distanceToLeftBottom <= range) {
            robotState.distanceToCorner = distanceToLeftBottom;
            robotState.corner = CORNER.LEFT_BOTTOM;
        } else if (distanceToRightBottom <= range) {
            robotState.distanceToCorner = distanceToRightBottom;
            robotState.corner = CORNER.RIGHT_BOTTOM;
        } else if (distanceToTopLeft <= range) {
            robotState.distanceToCorner = distanceToTopLeft;
            robotState.corner = CORNER.TOP_LEFT;
        } else if (distanceToTopRight <= range) {
            robotState.distanceToCorner = distanceToTopRight;
            robotState.corner = CORNER.TOP_RIGHT;
        } else {
            robotState.distanceToCorner = Double.MAX_VALUE;
            robotState.corner = CORNER.NO_CORNER;
        }
        return robotState.corner;
    }
    public enum CORNER {
        LEFT_BOTTOM,
        RIGHT_BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        NO_CORNER
    }

    public static boolean testIfRobotTurnAnOrientationIsOtherRobot(RobotState robotState, double relativeOrientation, double frameRange) {
        double orientation = robotState.orientation + relativeOrientation;
        if (orientation > Math.PI) {
            orientation = orientation - Math.PI - Math.PI;
        } else if (orientation < -Math.PI) {
            orientation = orientation + Math.PI + Math.PI;
        }
        for (RobotState otherRobotState : robotStates) {
            if (otherRobotState == robotState) {
                continue;
            }
            redundancy = RobotMetadata.R / 2;
            double distance = redundancy + robotState.carryingItemType == DO_NOT_CARRYING ? RobotMetadata.R : RobotMetadata.R_CARRYING + otherRobotState.carryingItemType == DO_NOT_CARRYING ? RobotMetadata.R : RobotMetadata.R_CARRYING;
            for (int i = 0; i <= frameRange; i++) {
                if (calculateDistance(robotState.x + RobotMetadata.FORWARD_MAX_VELOCITY * i * FRAME_LENGTH * Math.cos(orientation),
                        robotState.y + RobotMetadata.FORWARD_MAX_VELOCITY * i * FRAME_LENGTH * Math.sin(orientation),
                        otherRobotState.x + otherRobotState.linear_velocity_x * i,
                        otherRobotState.y + otherRobotState.linear_velocity_y * i) >= distance) {
                    return true;
                }
            }
        }
        return false;
    }
    public static boolean testIfRobotTurnAnOrientationIsBorder(RobotState robotState, double relativeOrientation, double frameRange) {
        double orientation = robotState.orientation + relativeOrientation;
        double x = robotState.x + frameRange * RobotMetadata.FORWARD_MAX_VELOCITY * Math.cos(orientation);
        double y = robotState.y + frameRange * RobotMetadata.FORWARD_MAX_VELOCITY * Math.sin(orientation);
        return x < 0 || y < 0 || x >= MAP_WIDTH || y >= MAP_WIDTH;
    }

    public static Double findOrientationToEscape(RobotState robotState, double frameRange) {
        double orientation = robotState.orientation;
        double[] addAngle = new double[]{-Math.PI / 4, Math.PI / 4, Math.PI / 2, -Math.PI / 2, Math.PI * 3 / 4, -Math.PI * 3 / 4, Math.PI, -Math.PI};
        for (int i = 0; i <= addAngle.length; i++) {
            orientation += addAngle[i];
            if (testIfRobotTurnAnOrientationIsBorder(robotState, orientation, frameRange) && testIfRobotTurnAnOrientationIsOtherRobot(robotState, orientation, frameRange)) {
                return addAngle[i];
            }
        }
        return null;
    }
    private static void handleCrashMovement(int robotIdA, RobotState robotStateA, RobotState robotStateB) {
        double orientationA = robotStateA.orientation;
        //double orientationB = robotStateB.orientation;
        double relativeDirectionRadiusA = calculateAngleInRadians(robotStateA.x, robotStateA.y, robotStateB.x, robotStateB.y); // the relativeDirectionRadius from A to B
        double relativeOrientationA = orientationA - relativeDirectionRadiusA;

        // turn left
        if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI / 8) { // right top
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY));
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI / 4) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY / 4 * 3));
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI * 3 / 8) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY / 2));
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI / 2) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY / 4));
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI * 5 / 8) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY / 4));
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI * 6 / 8) {
            if (robotStateA.linear_velocity_y < robotStateB.linear_velocity_y) {
                movementActions.put(robotIdA, new Action(robotStateB.linear_velocity_y - robotStateA.linear_velocity_y, RobotMetadata.ROTATE_MAX_VELOCITY / 4));
            }
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI * 7 / 8) {
            if (robotStateA.linear_velocity_y < robotStateB.linear_velocity_y) {
                movementActions.put(robotIdA, new Action(robotStateB.linear_velocity_y - robotStateA.linear_velocity_y, RobotMetadata.ROTATE_MAX_VELOCITY / 2));
            }
        } else if (relativeOrientationA >= 0 && relativeOrientationA <= Math.PI) {
            if (robotStateA.linear_velocity_y < robotStateB.linear_velocity_y) {
                movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, RobotMetadata.ROTATE_MAX_VELOCITY / 4));
            }
        }
        // turn right
        else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI / 8) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY,  -RobotMetadata.ROTATE_MAX_VELOCITY));
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI / 4) { // left top
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY,  -RobotMetadata.ROTATE_MAX_VELOCITY/ 4 * 3));
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI * 3 / 8) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY,  -RobotMetadata.ROTATE_MAX_VELOCITY / 2));
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI / 2) {
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY,  -RobotMetadata.ROTATE_MAX_VELOCITY / 4));
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI * 5 / 8){
            movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY,  -RobotMetadata.ROTATE_MAX_VELOCITY / 4));
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI * 6 / 8) {
            if (robotStateA.linear_velocity_y < robotStateB.linear_velocity_y) {
                movementActions.put(robotIdA, new Action(robotStateB.linear_velocity_y - robotStateA.linear_velocity_y, -RobotMetadata.ROTATE_MAX_VELOCITY / 4));
            }
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI * 7 / 8) {
            if (robotStateA.linear_velocity_y < robotStateB.linear_velocity_y) {
                movementActions.put(robotIdA, new Action(robotStateB.linear_velocity_y - robotStateA.linear_velocity_y, -RobotMetadata.ROTATE_MAX_VELOCITY / 2));
            }
        } else if (relativeOrientationA <= 0 && relativeOrientationA >= -Math.PI){
            if (robotStateA.linear_velocity_y < robotStateB.linear_velocity_y) {
                movementActions.put(robotIdA, new Action(RobotMetadata.FORWARD_MAX_VELOCITY, -RobotMetadata.ROTATE_MAX_VELOCITY / 4));
            }
        }
    }

    public static boolean calculateRawMaterialsIsEmpty(int rawMaterialsStatus, int type) {
        return ((rawMaterialsStatus >> type) & 1) == 0;
    }

    private static boolean readUtilOK() {
        String line;
        while (inStream.hasNextLine()) {
            line = inStream.nextLine();
            if ("OK".equals(line)) {
                return true;
            }
            // do something;
        }
        return false;
    }
    public static boolean handleEachFrameInputData(Map<Integer, List<WorkBenchState>> forWorkBenchStates) {
        String line = inStream.nextLine();
        String[] parts = line.split(" ");
        frameID = Integer.parseInt(parts[0]);
        curMoney = Integer.parseInt(parts[1]);
        int benchNum = inStream.nextInt();
        inStream.nextLine();
        if (workBenchStatesSequence == null) {
            workBenchStatesSequence = new ArrayList<>(benchNum);
        }
        for (int i = 0; i < benchNum; i++) {
            line = inStream.nextLine();
            parts = line.split(" ");
            int type = Integer.parseInt(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            int remainingFrameCount = Integer.parseInt(parts[3]);
            int rawMaterialsStatus = Integer.parseInt(parts[4]);
            int productStatus = Integer.parseInt(parts[5]);
            WorkBenchState state = new WorkBenchState(type, x, y, remainingFrameCount, rawMaterialsStatus, productStatus, i);
            if (forWorkBenchStates != null) {
                if (!forWorkBenchStates.containsKey(type)) {
                    forWorkBenchStates.put(type, new LinkedList<>());
                }
                forWorkBenchStates.get(type).add(state);
            }
            if (workBenchStatesSequence.size() != benchNum) {
                workBenchStatesSequence.add(state);
            } else {
                workBenchStatesSequence.set(i, state);
            }
        }
        for (int i = 0; i < 4; i++) {
            line = inStream.nextLine();
            parts = line.split(" ");
            int workbenchId = Integer.parseInt(parts[0]);
            int carryingItemType = Integer.parseInt(parts[1]);
            double timeValueFactor = Double.parseDouble(parts[2]);
            double collisionValueFactor = Double.parseDouble(parts[3]);
            double angular_velocity = Double.parseDouble(parts[4]);
            double linear_velocity_x = Double.parseDouble(parts[5]);
            double linear_velocity_y = Double.parseDouble(parts[6]);
            double orientation = Double.parseDouble(parts[7]);
            double x = Double.parseDouble(parts[8]);
            double y = Double.parseDouble(parts[9]);
            robotStates.get(i).setRobotStateParameters(workbenchId, carryingItemType, timeValueFactor, collisionValueFactor, angular_velocity, linear_velocity_x, linear_velocity_y, orientation, x, y);
        }
        while (inStream.hasNextLine()) {
            line = inStream.nextLine();
            if ("OK".equals(line)) {
                return false;
            } else if ("EOF".equals(line)) {
                return true;
            }
        }
        return false;
    }

    public static String instruction(String action, double robotId, double lineSpeed) {
        return String.format("%s %f %f\n", action, robotId, lineSpeed);
    }
    public static String instruction(String action, int robotId) {
        return String.format("%s %d\n", action, robotId);
    }

    public static double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    public static double calculateAngleInRadians(double x1, double y1, double x2, double y2) {
        double deltaX = x2 - x1;
        double deltaY = y2 - y1;
        // Calculate the radian of the coordinate to the positive half of x-axis
        return Math.atan2(deltaY, deltaX);
    }
    private static void handleInitMap() {
        String line;
        for (int row = 0; row < MAP_DATA_LENGTH; row++) {
            line = inStream.nextLine();
            if (row == 62) {
                for (int column = 0; column < line.length(); column++) {
                    if (Character.isDigit(line.charAt(column))) {
                        if (line.charAt(column) == '8') {
                            STRATEGY = NumberStrategy.STRATEGY_MAP_1;
                        }
                    }
                }
            } else if (row == 49) {
                for (int column = 0; column < line.length(); column++) {
                    if (Character.isDigit(line.charAt(column))) {
                        if (line.charAt(column) == '8') {
                            STRATEGY = NumberStrategy.STRATEGY_MAP_2;
                        }
                    }
                }
            } else if (row == 87) {
                for (int column = 0; column < line.length(); column++) {
                    if (Character.isDigit(line.charAt(column))) {
                        if (line.charAt(column) == '8') {
                            STRATEGY = NumberStrategy.STRATEGY_MAP_4;
                        }
                    }
                }
            }
        }
    }
    public static void requestOK() {
        String line;
        while (inStream.hasNextLine()) {
            line = inStream.nextLine();
            if ("OK".equals(line)) {
                outStream.println("OK");
                outStream.flush();
                return;
            }
        }
    }
    private static void handleInitRobotStates() {
        robotStates.add(new RobotState());
        robotStates.add(new RobotState());
        robotStates.add(new RobotState());
        robotStates.add(new RobotState());
    }

    private static void handleInitTaskChainsForStrategyEight(int taskChainNum) {
        for (int i = 0; i < taskChainNum; i++) {
            taskChainList.add(new TaskChain());
        }
        for (int robotId = 0; robotId < RobotNumber; robotId++) {
            robotStates.get(robotId).taskChain = taskChainList.get(robotId % taskChainNum);
        }
    }

    public enum  EconomicAction {
        SELL,
        PURCHASE,
    }

    public enum NumberStrategy {
        STRATEGY_MAP_3, // Move 1,2,3 to 9
        STRATEGY_MAP_1,
        STRATEGY_MAP_2,
        STRATEGY_MAP_4,
        STRATEGY3,
        STRATEGY4,
    }
    static class TaskChain {
        public List<Task> taskQueueFromStrategy = new LinkedList<>();
        public Map<Integer, WorkBenchState> usingFromWorkbenchStates = new HashMap<>();
        public Set<Integer> restrictiveConsumerWorkbenchType = new HashSet<>();

        public TaskChain() {
        }
        public void addTask(Task newTask) {
            taskQueueFromStrategy.add(0, newTask);
            newTask.fromWorkbench.isNotAssignedToTaskChain = false;
            usingFromWorkbenchStates.put(newTask.fromWorkbench.type, newTask.fromWorkbench);
        }
    }

    static class Action {
        double forward;
        double rotation;

        public Action(double forward, double rotation) {
            this.forward = forward;
            this.rotation = rotation;
        }
    }
    static class Task {
        public WorkBenchState fromWorkbench;
        public WorkBenchState toWorkbench;
        public double distanceFromToTo;
        public double profit;
        public double timeCost;
        public double rotation;
        public Double profitTimeCostRate;

        public Task(WorkBenchState fromWorkbench, WorkBenchState toWorkbench) {
            this.fromWorkbench = fromWorkbench;
            this.toWorkbench = toWorkbench;
        }

        public Task(WorkBenchState fromWorkbench, WorkBenchState toWorkbench, double distanceFromToTo) {
            this.fromWorkbench = fromWorkbench;
            this.toWorkbench = toWorkbench;
            this.distanceFromToTo = distanceFromToTo;
        }
    }

    public static double calculateX(double velocity, int prevFrame) {
        return velocity * FRAME_LENGTH * prevFrame + 0.5 * (RobotMetadata.F / RobotMetadata.M_CARRYING) * Math.pow(FRAME_LENGTH * prevFrame, 2);
    }
    static class RobotMetadata {
        public static final double R = 0.45;
        public static final double R_CARRYING = 0.53;
        public static final double economicActionAllowRange = 0.4;

        public static final double FORWARD_MAX_VELOCITY = 6.0;
        public static final double FORWARD_MIN_VELOCITY = -2.0;

        public static final double F = 250;
        public static final double M = 20 * R * R * Math.PI;
        public static final double M_CARRYING = 20 * R_CARRYING * R_CARRYING * Math.PI;
        public static int PREV_FRAME = 25;
        public static final double X = FORWARD_MAX_VELOCITY * FRAME_LENGTH * PREV_FRAME + 0.5 * (F / M) * Math.pow(FRAME_LENGTH * PREV_FRAME, 2);
        public static final double X_CARRYING = FORWARD_MAX_VELOCITY * FRAME_LENGTH * PREV_FRAME + 0.5 * (F / M_CARRYING) * Math.pow(FRAME_LENGTH * PREV_FRAME, 2);
        public static final double ROTATE_MAX_VELOCITY = Math.PI;

        public static final double MAXIMUM_TORQUE_F = 50;

        public static String FORWARD = "forward";
        public static String ROTATE = "rotate";
        public static String BUT = "buy";
        public static String SELL = "sell";
        public static String DESTROY = "destroy";
    }

    static class MaterialInfoNode {
        public int type;
        public int[] parentTypes;
        public int[] childrenTypes;

        public MaterialInfoNode(int type, int[] parentTypes, int[] childrenTypes) {
            this.type = type;
            this.parentTypes = parentTypes;
            this.childrenTypes = childrenTypes;
        }
    }

    static class WorkbenchMetadata {
        public static final double R = 0.25;

        public static final List<MaterialInfoNode> materialInfoNodes = new ArrayList<>(10);
        // Index start from 1
        public static final int[] purchasePrices = new int[]{0, 3000, 4400, 5800, 15400, 17200, 19200, 76000, 0, 0};
        public static final int[] sellingPrices = new int[]{0, 6000, 7600, 9200, 22500, 25000, 27500, 105000, 0, 0};
        public static final int[] frameCounts = new int[]{0, 50, 50, 50, 500, 500, 500, 1000, 0, 0};

        static {
            materialInfoNodes.add(null);
            materialInfoNodes.add(new MaterialInfoNode(1, new int[]{4, 5, 9}, null));
            materialInfoNodes.add(new MaterialInfoNode(2, new int[]{4, 6, 9}, null));
            materialInfoNodes.add(new MaterialInfoNode(3, new int[]{5, 6, 9}, null));
            materialInfoNodes.add(new MaterialInfoNode(4, new int[]{7, 9}, new int[]{1, 2}));
            materialInfoNodes.add(new MaterialInfoNode(5, new int[]{7, 9}, new int[]{1, 3}));
            materialInfoNodes.add(new MaterialInfoNode(6, new int[]{7, 9}, new int[]{2, 3}));
            materialInfoNodes.add(new MaterialInfoNode(7, new int[]{8, 9}, new int[]{4, 5, 6}));
            materialInfoNodes.add(new MaterialInfoNode(8, null, new int[]{7}));
            materialInfoNodes.add(new MaterialInfoNode(9, null, new int[]{1, 2, 3, 4, 5, 6, 7}));
        }
    }
    static class RobotState {
        public int workbenchId;
        public int carryingItemType;
        public double collisionValueFactor;
        public double timeValueFactor;

        public double angular_velocity;
        public double linear_velocity_x;
        public double linear_velocity_y;
        public double orientation;
        public double x;
        public double y;
        private Task task;
        private TaskChain taskChain;
        private CORNER corner;
        private double distanceToCorner;

        public Task getTask() {
            return task;
        }

        public WorkBenchState lastBeenToWorkbench;

        public EconomicAction economicAction = EconomicAction.PURCHASE;

        public void setTask(Task task) {
            if (task != null) {
                task.toWorkbench.readyToGetMaterialTypes.add(task.fromWorkbench.type);
                task.fromWorkbench.readyToBeTaken = true;
            }
            this.task = task;
        }

        public void setRobotStateParameters(int workbenchId, int carryingItemType, double collisionValueFactor, double timeValueFactor, double angular_velocity, double linear_velocity_x, double linear_velocity_y, double orientation, double x, double y) {
            this.workbenchId = workbenchId;
            this.carryingItemType = carryingItemType;
            this.collisionValueFactor = collisionValueFactor;
            this.timeValueFactor = timeValueFactor;
            this.angular_velocity = angular_velocity;
            this.linear_velocity_x = linear_velocity_x;
            this.linear_velocity_y = linear_velocity_y;
            this.orientation = orientation;
            this.x = x;
            this.y = y;
        }
        public RobotState() {
        }
    }

    static class WorkBenchState{
        public int type;
        public double x;
        public double y;
        public int remainingFrameCount;
        public int rawMaterialsStatus;
        public int productStatus;
        public int index;
        public WorkBenchState(int type, double x, double y, int remainingFrameCount, int rawMaterialsStatus, int productStatus, int index) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.remainingFrameCount = remainingFrameCount;
            this.rawMaterialsStatus = rawMaterialsStatus;
            this.productStatus = productStatus;
            this.index = index;
        }
        public boolean readyToBeTaken = false;
        public Set<Integer> readyToGetMaterialTypes = new HashSet<>();
        public double distance;
        public WorkBenchState bindingConsumerWorkbench;
        public boolean isNotAssignedToTaskChain = true;
    }
}
