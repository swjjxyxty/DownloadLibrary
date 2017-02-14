package com.bestxty.dl;

import android.net.NetworkInfo;
import android.net.Uri;
import android.util.Log;

import com.bestxty.dl.Downloader.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bestxty.dl.Utils.DEFAULT_BUFFER_SIZE;
import static com.bestxty.dl.Utils.EOF;
import static com.bestxty.dl.Utils.closeQuietly;
import static com.bestxty.dl.Utils.createTargetFile;
import static com.bestxty.dl.Utils.log;

/**
 * @author swjjx
 *         Created by swjjx on 2017/1/24. for DownloadLibrary
 */
class SaultTaskHunter implements TaskHunter, HunterStatusListener {

    private static final AtomicInteger SEQUENCE_GENERATOR = new AtomicInteger();

    private final int sequence;
    private final Task task;
    private final Sault sault;
    private final Dispatcher dispatcher;
    private final Downloader downloader;

    private Exception exception;
    private Future<?> future;
    private List<InternalTaskHunter> taskHunterList;

    SaultTaskHunter(Sault sault,
                    Dispatcher dispatcher,
                    Task task,
                    Downloader downloader) {
        this.sault = sault;
        this.dispatcher = dispatcher;
        this.task = task;
        this.downloader = downloader;
        this.sequence = SEQUENCE_GENERATOR.incrementAndGet();
        this.taskHunterList = new ArrayList<>();
    }


    @Override
    public synchronized void onProgress(long length) {
        task.finishedSize += length;
        Utils.ProgressInformer progress = new Utils.ProgressInformer(task.getTag(), task.getCallback());

        progress.totalSize = task.totalSize;

        progress.finishedSize = task.finishedSize;
        dispatcher.dispatchProgress(progress);
    }

    @Override
    public void onFinish(InternalTaskHunter hunter) {
        taskHunterList.remove(hunter);

        if (taskHunterList.isEmpty()) {
            dispatcher.dispatchComplete(this);
        }
    }

    @Override
    public void run() {
        if (!task.isMultiThreadEnabled()) {

            try {
                Log.d("SaultTaskHunter", "task.isBreakPointEnabled():" + task.isBreakPointEnabled());
                boolean needResume = task.finishedSize != 0
                        && task.isBreakPointEnabled()
                        && downloader.supportBreakPoint();

                Log.d("SaultTaskHunter", "needResume:" + needResume);

                Response response = needResume ? downloader.load(task.getUri(), task.finishedSize)
                        : downloader.load(task.getUri());

//                Response response = downloader.load(task.getUri());

                InputStream stream = response.stream;
                if (stream == null) {
                    System.out.println("stream is null");
                    return;
                }

                if (response.contentLength == 0) {
                    closeQuietly(stream);
                    throw new Downloader.ContentLengthException("Received response with 0 content-length header.");
                }
                try {
                    createTargetFile(task.getTarget());

                    RandomAccessFile output = new RandomAccessFile(task.getTarget(), "rw");
                    Utils.ProgressInformer progress = new Utils.ProgressInformer(task.getTag(), task.getCallback());

                    if (needResume) {
                        output.seek(task.finishedSize);
                        progress.totalSize = task.totalSize;
                    } else {
                        progress.totalSize = response.contentLength;
                        task.totalSize = response.contentLength;
                        task.finishedSize = 0;
                    }

                    try {
                        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                        int length;
                        while (EOF != (length = stream.read(buffer))) {
                            output.write(buffer, 0, length);
                            task.finishedSize += length;
                            progress.finishedSize = task.finishedSize;
                            dispatcher.dispatchProgress(progress);
                        }

                        output.close(); // don't swallow close Exception if copy completes normally
                    } finally {
                        closeQuietly(output);
                    }
                } finally {
                    closeQuietly(stream);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            dispatcher.dispatchComplete(this);
            return;
        }
        try {

            boolean needResume = task.finishedSize != 0
                    && task.isBreakPointEnabled()
                    && downloader.supportBreakPoint();

            Log.d("SaultTaskHunter", "needResume:" + needResume);


            if (!needResume) {
                task.totalSize = downloader.fetchContentLength(task.getUri());
                task.splitTask();
            }

            log(task.getSubTaskList().size() + "-----------list size");

            for (Task.SubTask subTask : task.getSubTaskList()) {
                log(subTask.toString());
                if (subTask.isDone()) {
                    log("subtask is done break. " + subTask.toString());
                    continue;
                }

                InternalTaskHunter taskHunter = new InternalTaskHunter(subTask, task.getUri(), downloader, task.getTarget(), this);
                taskHunterList.add(taskHunter);
                Future future = dispatcher.submit(taskHunter);
                taskHunter.setFuture(future);
            }


        } catch (Exception e) {
            e.printStackTrace();
            exception = e;
        }
    }

    @Override
    public Sault getSault() {
        return sault;
    }

    @Override
    public Task getTask() {
        return task;
    }

    @Override
    public Exception getException() {
        return exception;
    }

    @Override
    public String getKey() {
        return task.getKey();
    }

    @Override
    public Sault.Priority getPriority() {
        return task.getPriority();
    }

    @Override
    public int getSequence() {
        return sequence;
    }

    @Override
    public boolean cancel() {
        for (InternalTaskHunter taskHunter : taskHunterList) {
            if (!taskHunter.cancel()) return false;
        }
        return future != null && (future.isDone() || future.cancel(true));

    }

    @Override
    public boolean isCancelled() {
        return future != null && future.isCancelled();
    }

    @Override
    public boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
        return false;
    }

    @Override
    public void setFuture(Future<?> future) {
        this.future = future;
    }


    public static class InternalTaskHunter implements Runnable, TaskHunter {

        private final Task.SubTask subTask;
        private final Uri uri;
        private final Downloader downloader;
        private final File target;
        private final HunterStatusListener listener;

        private Future<?> future;

        InternalTaskHunter(Task.SubTask subTask, Uri uri, Downloader downloader, File target, HunterStatusListener listener) {
            this.subTask = subTask;
            this.uri = uri;
            this.downloader = downloader;
            this.target = target;
            this.listener = listener;
        }

        public void setFuture(Future<?> future) {
            this.future = future;
        }

        public boolean cancel() {

            return future != null && (future.isDone() || future.cancel(true));

            //            return (future != null && future.isDone()) || (future != null
//                    && future.cancel(true));
        }


        public boolean isCancelled() {
            return future != null && future.isCancelled();
        }

        @Override
        public void run() {
            try {
                receiveContent(subTask);
                listener.onFinish(this);
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        private void receiveContent(Task.SubTask subTask) throws IOException {
            final long finishedSize = subTask.finishedSize;

            final long startPosition = subTask.getStartPosition() + finishedSize;
            final long endPosition = subTask.getEndPosition();

            Response response = downloader.load(uri, startPosition, endPosition);

            InputStream stream = response.stream;
            if (stream == null) {
                System.out.println("stream is null");
                return;
            }

            if (response.contentLength == 0) {
                closeQuietly(stream);
                throw new Downloader.ContentLengthException("Received response with 0 content-length header.");
            }
            try {
                createTargetFile(target);

                RandomAccessFile output = new RandomAccessFile(target, "rw");

                output.seek(startPosition);

                try {
                    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                    int length;
                    while (EOF != (length = stream.read(buffer))) {
                        output.write(buffer, 0, length);
                        subTask.finishedSize += length;
                        listener.onProgress(length);
                    }

                    output.close(); // don't swallow close Exception if copy completes normally
                } finally {
                    closeQuietly(output);
                }
            } finally {
                closeQuietly(stream);
                log("done." + subTask.finishedSize);

            }
        }


        @Override
        public Sault getSault() {
            return null;
        }

        @Override
        public Task getTask() {
            return null;
        }

        @Override
        public Exception getException() {
            return null;
        }

        @Override
        public String getKey() {
            return null;
        }

        @Override
        public Sault.Priority getPriority() {
            return Sault.Priority.NORMAL;
        }

        @Override
        public int getSequence() {
            return 0;
        }

        @Override
        public boolean shouldRetry(boolean airplaneMode, NetworkInfo info) {
            return false;
        }
    }
}
