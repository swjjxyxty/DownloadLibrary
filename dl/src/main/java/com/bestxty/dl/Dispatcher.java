package com.bestxty.dl;

import android.Manifest;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.bestxty.dl.Utils.DISPATCHER_THREAD_NAME;
import static com.bestxty.dl.Utils.THREAD_PREFIX;
import static com.bestxty.dl.Utils.EventInformer;
import static com.bestxty.dl.Utils.ErrorInformer;
import static com.bestxty.dl.Utils.ProgressInformer;
import static com.bestxty.dl.Callback.*;
import static com.bestxty.dl.Utils.getService;
import static com.bestxty.dl.Utils.hasPermission;

/**
 * @author xty
 *         Created by xty on 2016/12/9.
 */
class Dispatcher {

    static final int TASK_SUBMIT = 1;
    static final int TASK_PAUSE = 2;
    static final int TASK_RESUME = 3;
    static final int TASK_CANCEL = 4;
    static final int TASK_EVENT = 5;
    static final int HUNTER_COMPLETE = 6;
    static final int HUNTER_FAILED = 7;
    static final int HUNTER_RETRY = 8;
    static final int TASK_BATCH_RESUME = 9;
    static final int HUNTER_DELAY_NEXT_BATCH = 10;
    static final int HUNTER_BATCH_COMPLETE = 11;
    static final int HUNTER_PROGRESS = 12;
    static final int HUNTER_EROOR = 13;

    private static final int BATCH_DELAY = 200; // ms
    private static final int RETRY_DELAY = 500;

    private final ExecutorService service;
    private final DispatcherThread dispatcherThread;
    private final Handler handler;
    private final Handler mainThreadHandler;
    private final Map<String, TaskHunter> hunterMap;
    private final Map<String, Task> pausedTaskMap;
    private final Map<String, Task> failedTaskMap;
    private final Downloader downloader;
    private final Set<Object> pausedTags;
    private final List<TaskHunter> batch;
    private final boolean scansNetworkChanges;
    private final Context context;
    private boolean airplaneMode;

    Dispatcher(Context context,
               ExecutorService service,
               Handler mainThreadHandler,
               Downloader downloader) {
        this.context = context;
        this.service = service;
        this.mainThreadHandler = mainThreadHandler;
        this.downloader = downloader;
        this.dispatcherThread = new DispatcherThread();
        dispatcherThread.start();
        this.handler = new DispatcherHandler(dispatcherThread.getLooper(), this);
        this.hunterMap = new LinkedHashMap<>();
        this.pausedTaskMap = new HashMap<>();
        this.failedTaskMap = new HashMap<>();
        this.pausedTags = new HashSet<>();
        this.batch = new ArrayList<>(4);
        this.scansNetworkChanges = hasPermission(context, Manifest.permission.ACCESS_NETWORK_STATE);

    }


    void shutdown() {
        // Shutdown the thread pool only if it is the one created by Picasso.
        if (service instanceof SaultExecutorService) {
            service.shutdown();
        }
        dispatcherThread.quit();
//         Unregister network broadcast receiver on the main thread.
//        Picasso.HANDLER.post(new Runnable() {
//            @Override public void run() {
//                receiver.unregister();
//            }
//        });
    }


    Stats getStats() {
        Stats stats = new Stats();
        stats.hunterMapSize = hunterMap.size();
        stats.batchSize = batch.size();
        stats.failedTaskSize = failedTaskMap.size();
        stats.pausedTagSize = pausedTags.size();
        stats.pausedTaskSize = pausedTaskMap.size();
        return stats;
    }

    private void dispatchError(ErrorInformer errorInformer) {
        mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(HUNTER_EROOR, errorInformer));
    }

    private void dispatchEvent(EventInformer eventInformer) {
        System.out.println("dispatch event." + eventInformer.event);
        mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(TASK_EVENT, eventInformer));
    }

    void dispatchProgress(ProgressInformer progressInformer) {
        mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(HUNTER_PROGRESS,
                ProgressInformer.from(progressInformer)));
    }

    void dispatchSubmit(Task task) {
        System.out.println("dispatch submit.task=" + task.getKey());
        handler.sendMessage(handler.obtainMessage(TASK_SUBMIT, task));
    }

    void dispatchPauseTag(Object tag) {
        System.out.println(String.format(Locale.CHINA, "dispatch pause. paused size=%d,paused tag=%d,failed size=%d,hunter size=%d.",
                pausedTaskMap.size(),
                pausedTags.size(),
                failedTaskMap.size(),
                hunterMap.size()));
        handler.sendMessage(handler.obtainMessage(TASK_PAUSE, tag));
    }

    void dispatchCancel(Task task) {
        System.out.println("dispatch cancel.");
        handler.sendMessage(handler.obtainMessage(TASK_CANCEL, task));
    }

    void dispatchResumeTag(Object tag) {
        handler.sendMessage(handler.obtainMessage(TASK_RESUME, tag));
    }

    void dispatchComplete(TaskHunter hunter) {
        handler.sendMessage(handler.obtainMessage(HUNTER_COMPLETE, hunter));
    }

    void dispatchFailed(TaskHunter hunter) {
        System.out.println("dispatch task failed. ex=" + hunter.getException().getMessage());
        hunter.getException().printStackTrace();
        handler.sendMessage(handler.obtainMessage(HUNTER_FAILED, hunter));
    }

    void dispatchRetry(TaskHunter hunter) {
        System.out.println("dispatch task retry.ex=" + hunter.getException().getMessage());
        hunter.getException().printStackTrace();
        handler.sendMessageDelayed(handler.obtainMessage(HUNTER_RETRY, hunter), RETRY_DELAY);
    }


    private void performSubmit(Task task) {
        System.out.println("perform submit task,task=" + task.getKey());
        TaskHunter hunter = new TaskHunter(task.getSault(), this, task, downloader);
        hunter.future = service.submit(hunter);
        hunterMap.put(hunter.getKey(), hunter);
        System.out.println("put hunter to hunter map. size=" + hunterMap.size());
        dispatchEvent(EventInformer.fromTask(task, EVENT_START));
    }

    private void performPause(Object tag) {
        if (!pausedTags.add(tag)) {
            System.out.println("tag is already in paused tag set.");
            return;
        }

        System.out.println("ready pause task for tag:" + tag);
        for (Iterator<TaskHunter> iterator = hunterMap.values().iterator(); iterator.hasNext(); ) {
            TaskHunter hunter = iterator.next();
            Task single = hunter.getTask();
            if (single == null) {
                continue;
            }

            if (single.getTag().equals(tag)) {
                System.out.println("find task");
                hunter.detach(single);
                System.out.println("detach task for hunter");
                if (hunter.cancel()) {
                    System.out.println("cancel hunter");
                    iterator.remove();
                    System.out.println("put task to paused task map");
                    pausedTaskMap.put(single.getKey(), single);
                    dispatchEvent(EventInformer.fromTask(single, EVENT_PAUSE));
                }
            }
        }
        System.out.println("perform pause finish");
    }

    private void performResume(Object tag) {
        if (!pausedTags.remove(tag)) {
            System.out.println("paused tag set not contain tag.");
            return;
        }
        System.out.println("ready resume task for tag:" + tag);
        List<Task> batch = null;
        for (Iterator<Task> iterator = pausedTaskMap.values().iterator(); iterator.hasNext(); ) {
            Task task = iterator.next();
            if (task.getTag().equals(tag)) {
                if (batch == null) {
                    batch = new ArrayList<>();
                }
                batch.add(task);
                iterator.remove();
            }
        }

        if (batch != null) {
            System.out.println("find need resumed task size=" + batch.size());
            mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(TASK_BATCH_RESUME, batch));
        } else {
            System.out.println("not found need resumed task");
        }
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    private void performCancel(Task task) {
        System.out.println("perform cancel.");
        String key = task.getKey();
        TaskHunter hunter = hunterMap.get(key);
        if (hunter != null) {
            System.out.println("find hunter");
            hunter.detach(task);
            if (hunter.cancel()) {
                System.out.println("cancel hunter");
                dispatchEvent(EventInformer.fromTask(task, EVENT_CANCEL));
                hunterMap.remove(key);
            }
        }

        if (pausedTags.contains(task.getTag())) {
            System.out.println("paused tags contain task");
            pausedTaskMap.remove(key);
            pausedTags.remove(task.getTag());
            dispatchEvent(EventInformer.fromTask(task, EVENT_CANCEL));
        }

        Task remove = failedTaskMap.remove(key);
        if (remove != null) {
            System.out.println("task removed from failed task map");
            dispatchEvent(EventInformer.fromTask(task, EVENT_CANCEL));
        }
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    private void performComplete(TaskHunter hunter) {
        hunterMap.remove(hunter.getKey());
        batch(hunter);
    }

    private void performBatchComplete() {
        List<TaskHunter> copy = new ArrayList<>(batch);
        batch.clear();
        mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(HUNTER_BATCH_COMPLETE, copy));
    }


    private void performRetry(TaskHunter hunter) {
        if (hunter.isCancelled()) return;

        if (service.isShutdown()) {
            performError(hunter);
            return;
        }

        NetworkInfo networkInfo = null;
        if (scansNetworkChanges) {
            ConnectivityManager connectivityManager = getService(context, CONNECTIVITY_SERVICE);
            networkInfo = connectivityManager.getActiveNetworkInfo();
        }

        boolean hasConnectivity = networkInfo != null && networkInfo.isConnected();
        boolean shouldRetryHunter = hunter.shouldRetry(airplaneMode, networkInfo);
//        boolean supportsReplay = hunter.supportsReplay();


        if (!shouldRetryHunter) {
            performError(hunter);
            return;
        }


        // If we don't scan for network changes (missing permission) or if we have connectivity, retry.
        if (!scansNetworkChanges || hasConnectivity) {
            hunter.future = service.submit(hunter);
            return;
        }

        performError(hunter);

//        if (supportsReplay) {
//            markForReplay(hunter);
//        }
    }

    private void performError(TaskHunter hunter) {
        hunterMap.remove(hunter.getKey());
        dispatchError(ErrorInformer.fromTask(hunter.getTask().getCallback(),
                new DownloadException(hunter.getKey(),
                        hunter.getTask().getUri().toString(),
                        hunter.getException())));
        batch(hunter);
    }

    private void batch(TaskHunter hunter) {
        if (hunter.isCancelled()) {
            return;
        }
        batch.add(hunter);
        if (!handler.hasMessages(HUNTER_DELAY_NEXT_BATCH)) {
            handler.sendEmptyMessageDelayed(HUNTER_DELAY_NEXT_BATCH, BATCH_DELAY);
        }
    }

    private static class DispatcherHandler extends Handler {
        private final Dispatcher dispatcher;

        DispatcherHandler(Looper looper, Dispatcher dispatcher) {
            super(looper);
            this.dispatcher = dispatcher;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case TASK_SUBMIT: {
                    Task task = (Task) msg.obj;
                    dispatcher.performSubmit(task);
                    break;
                }
                case TASK_PAUSE: {
                    Object tag = msg.obj;
                    dispatcher.performPause(tag);
                    break;
                }
                case TASK_RESUME: {
                    Object tag = msg.obj;
                    dispatcher.performResume(tag);
                    break;
                }
                case TASK_CANCEL: {
                    Task task = (Task) msg.obj;
                    dispatcher.performCancel(task);
                    break;
                }
                case HUNTER_COMPLETE: {
                    TaskHunter hunter = (TaskHunter) msg.obj;
                    dispatcher.performComplete(hunter);
                    break;
                }
                case HUNTER_FAILED: {
                    TaskHunter hunter = (TaskHunter) msg.obj;
                    dispatcher.performError(hunter);
                    break;
                }
                case HUNTER_RETRY: {
                    TaskHunter hunter = (TaskHunter) msg.obj;
                    dispatcher.performRetry(hunter);
                    break;
                }
                case HUNTER_DELAY_NEXT_BATCH: {
                    dispatcher.performBatchComplete();
                    break;
                }
            }
        }
    }

    private static class DispatcherThread extends HandlerThread {
        DispatcherThread() {
            super(THREAD_PREFIX + DISPATCHER_THREAD_NAME, THREAD_PRIORITY_BACKGROUND);
        }
    }
}