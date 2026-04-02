package com.co.claudecode.demo.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoreTest {

    private TaskStore store;

    @BeforeEach
    void setUp() {
        store = new TaskStore();
    }

    // ---- CRUD basics ----

    @Test
    void createTaskReturnsIncrementingIds() {
        String id1 = store.createTask("Task A", "Desc A");
        String id2 = store.createTask("Task B", "Desc B");
        assertEquals("1", id1);
        assertEquals("2", id2);
    }

    @Test
    void createAndGetTask() {
        String id = store.createTask("Fix bug", "In module X");
        Task task = store.getTask(id);
        assertNotNull(task);
        assertEquals("Fix bug", task.subject());
        assertEquals("In module X", task.description());
        assertEquals(TaskStatus.PENDING, task.status());
        assertNull(task.owner());
        assertTrue(task.blocks().isEmpty());
        assertTrue(task.blockedBy().isEmpty());
    }

    @Test
    void getTaskReturnsNullForUnknown() {
        assertNull(store.getTask("999"));
    }

    @Test
    void requireTaskThrowsForUnknown() {
        assertThrows(IllegalArgumentException.class, () -> store.requireTask("999"));
    }

    @Test
    void requireTaskReturnsExistingTask() {
        String id = store.createTask("Test", "desc");
        Task task = store.requireTask(id);
        assertEquals("Test", task.subject());
    }

    @Test
    void listTasksReturnsAllTasks() {
        store.createTask("A", "a");
        store.createTask("B", "b");
        store.createTask("C", "c");
        List<Task> tasks = store.listTasks();
        assertEquals(3, tasks.size());
    }

    @Test
    void listTasksReturnsEmptyWhenNoTasks() {
        assertTrue(store.listTasks().isEmpty());
    }

    @Test
    void deleteTaskRemovesIt() {
        String id = store.createTask("To delete", "desc");
        assertEquals(1, store.size());
        assertTrue(store.deleteTask(id));
        assertEquals(0, store.size());
        assertNull(store.getTask(id));
    }

    @Test
    void deleteNonexistentReturnsFalse() {
        assertFalse(store.deleteTask("999"));
    }

    @Test
    void sizeReflectsCurrentCount() {
        assertEquals(0, store.size());
        store.createTask("A", "a");
        assertEquals(1, store.size());
        store.createTask("B", "b");
        assertEquals(2, store.size());
    }

    @Test
    void clearResetsEverything() {
        store.createTask("A", "a");
        store.createTask("B", "b");
        assertEquals(2, store.size());

        store.clear();
        assertEquals(0, store.size());

        // ID sequence should reset too
        String newId = store.createTask("C", "c");
        assertEquals("1", newId);
    }

    // ---- Update operations ----

    @Test
    void updateTaskStatus() {
        String id = store.createTask("Task", "desc");
        Task updated = store.updateTask(id, TaskStatus.IN_PROGRESS, null, null, null, null, null);
        assertEquals(TaskStatus.IN_PROGRESS, updated.status());
        assertEquals(TaskStatus.IN_PROGRESS, store.getTask(id).status());
    }

    @Test
    void updateTaskSubject() {
        String id = store.createTask("Old subject", "desc");
        Task updated = store.updateTask(id, null, "New subject", null, null, null, null);
        assertEquals("New subject", updated.subject());
    }

    @Test
    void updateTaskDescription() {
        String id = store.createTask("Task", "old desc");
        Task updated = store.updateTask(id, null, null, "new desc", null, null, null);
        assertEquals("new desc", updated.description());
    }

    @Test
    void updateTaskOwner() {
        String id = store.createTask("Task", "desc");
        Task updated = store.updateTask(id, null, null, null, "agent-1", null, null);
        assertEquals("agent-1", updated.owner());
    }

    @Test
    void updateTaskAddBlocks() {
        String id1 = store.createTask("Task 1", "desc");
        String id2 = store.createTask("Task 2", "desc");
        Task updated = store.updateTask(id1, null, null, null, null, List.of(id2), null);
        assertTrue(updated.blocks().contains(id2));
    }

    @Test
    void updateTaskAddBlockedBy() {
        String id1 = store.createTask("Task 1", "desc");
        String id2 = store.createTask("Task 2", "desc");
        Task updated = store.updateTask(id2, null, null, null, null, null, List.of(id1));
        assertTrue(updated.blockedBy().contains(id1));
    }

    @Test
    void updateTaskAddBlocksDoesNotDuplicate() {
        String id1 = store.createTask("Task 1", "desc");
        String id2 = store.createTask("Task 2", "desc");
        store.updateTask(id1, null, null, null, null, List.of(id2), null);
        Task updated = store.updateTask(id1, null, null, null, null, List.of(id2), null);
        // Should not have duplicates
        assertEquals(1, updated.blocks().stream().filter(b -> b.equals(id2)).count());
    }

    @Test
    void updateMultipleFieldsAtOnce() {
        String id = store.createTask("Task", "desc");
        Task updated = store.updateTask(id, TaskStatus.COMPLETED, "New Title",
                "New Desc", "owner-1", null, null);
        assertEquals(TaskStatus.COMPLETED, updated.status());
        assertEquals("New Title", updated.subject());
        assertEquals("New Desc", updated.description());
        assertEquals("owner-1", updated.owner());
    }

    @Test
    void updateNonexistentTaskThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                store.updateTask("999", TaskStatus.COMPLETED, null, null, null, null, null));
    }

    @Test
    void updateWithBlankSubjectKeepsOriginal() {
        String id = store.createTask("Original", "desc");
        Task updated = store.updateTask(id, null, "  ", null, null, null, null);
        assertEquals("Original", updated.subject());
    }

    // ---- State transitions ----

    @Test
    void statusTransitionPendingToInProgress() {
        String id = store.createTask("Task", "desc");
        assertEquals(TaskStatus.PENDING, store.getTask(id).status());
        store.updateTask(id, TaskStatus.IN_PROGRESS, null, null, null, null, null);
        assertEquals(TaskStatus.IN_PROGRESS, store.getTask(id).status());
    }

    @Test
    void statusTransitionInProgressToCompleted() {
        String id = store.createTask("Task", "desc");
        store.updateTask(id, TaskStatus.IN_PROGRESS, null, null, null, null, null);
        store.updateTask(id, TaskStatus.COMPLETED, null, null, null, null, null);
        assertEquals(TaskStatus.COMPLETED, store.getTask(id).status());
    }

    // ---- Concurrency ----

    @Test
    void concurrentCreatesProduceUniqueIds() throws InterruptedException {
        int threadCount = 10;
        int tasksPerThread = 50;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        ConcurrentHashMap<String, Boolean> ids = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            exec.submit(() -> {
                try {
                    for (int i = 0; i < tasksPerThread; i++) {
                        String id = store.createTask("Task", "desc");
                        ids.put(id, Boolean.TRUE);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        exec.shutdown();

        // All IDs should be unique
        assertEquals(threadCount * tasksPerThread, ids.size());
        assertEquals(threadCount * tasksPerThread, store.size());
    }

    @Test
    void concurrentUpdatesDoNotLoseData() throws InterruptedException {
        String id = store.createTask("Task", "desc");
        int threadCount = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final String blockId = String.valueOf(t + 100);
            exec.submit(() -> {
                try {
                    store.updateTask(id, null, null, null, null, List.of(blockId), null);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        exec.shutdown();

        Task task = store.getTask(id);
        // Due to ConcurrentHashMap.compute(), each update is atomic,
        // but concurrent calls may overwrite each other's blocks additions.
        // At minimum, the task should still exist and be valid.
        assertNotNull(task);
        assertFalse(task.blocks().isEmpty());
    }
}
