package lld.notificationSystem;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.*;
import java.util.*;

class AsyncNotificationEngine {
    private final BlockingQueue<NotificationRequest> queue;
    private final ExecutorService executorService;
    private final NotificationService service;
    private volatile boolean running = true;

    public AsyncNotificationEngine(int threadCount, NotificationService service) {
        this.queue = new LinkedBlockingQueue<>(1000); // Buffer size of 1000
        this.executorService = Executors.newFixedThreadPool(threadCount);
        this.service = service;
        startConsumers(threadCount);
    }

    private void startConsumers(int threadCount) {
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                while (running || !queue.isEmpty()) {
                    try {
                        NotificationRequest request = queue.poll(1, TimeUnit.SECONDS);
                        if (request != null) {
                            // The actual logic of checking preferences and sending
                            service.sendNotification(request);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
    }

    public void enqueue(NotificationRequest request) {
        boolean added = queue.offer(request);
        if (!added) {
            System.err.println("Queue Full! Dropping request for user: " + request.userId);
            // In production, move to a Dead Letter Queue (DLQ) or Database
        }
    }

    public void shutdown() {
        running = false;
        executorService.shutdown();
    }
}