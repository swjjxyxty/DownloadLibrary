package com.bestxty.sault;

import com.bestxty.sault.Utils.ProgressInformer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

import static com.bestxty.sault.Utils.THREAD_IDLE_NAME;
import static com.bestxty.sault.Utils.closeQuietly;
import static com.bestxty.sault.Utils.createTargetFile;
import static com.bestxty.sault.Utils.log;
import static java.lang.Thread.currentThread;

/**
 * @author xty
 *         Created by xty on 2017/2/18.
 */
class SaultMultiPartTaskHunter extends BaseSaultTaskHunter implements HunterStatusListener {


    private static final int LENGTH_PER_THREAD = 1024 * 1024 * 10;      //10M

    private List<TaskHunter> taskHunterList;

    private ProgressInformer progressInformer;

    SaultMultiPartTaskHunter(Sault sault, Dispatcher dispatcher, Task task, Downloader downloader) {
        super(sault, dispatcher, task, downloader);
        taskHunterList = new ArrayList<>();
        progressInformer = ProgressInformer.create(task);
    }


    @Override
    public void onProgress(long length) {
        task.finishedSize += length;
        progressInformer.finishedSize = task.finishedSize;
        dispatcher.dispatchProgress(progressInformer);
    }


    @Override
    public void onFinish(TaskHunter hunter) {
        taskHunterList.remove(hunter);

        if (taskHunterList.isEmpty()) {
            task.endTime = System.nanoTime();
            dispatcher.dispatchComplete(this);
            progressInformer = null;
        }

    }

    private void calculateTaskCount() throws IOException {

        List<Task> subTaskList = task.getSubTaskList();
        long totalSize = downloader.fetchContentLength(task.getUri());

        task.totalSize = totalSize;
        progressInformer.totalSize = totalSize;

        createTargetFile(task.getTarget());

        RandomAccessFile targetFile = new RandomAccessFile(task.getTarget(), "rw");

        targetFile.setLength(totalSize);

        closeQuietly(targetFile);

        long threadSize;
        long threadLength = LENGTH_PER_THREAD;
        if (totalSize <= LENGTH_PER_THREAD) {
            threadSize = 2;
            threadLength = totalSize / threadSize;
        } else {
            threadSize = totalSize / LENGTH_PER_THREAD;
        }
        long remainder = totalSize % threadLength;
        for (int i = 0; i < threadSize; i++) {
            long start = i * threadLength;
            long end = start + threadLength - 1;
            if (i == threadSize - 1) {
                end = start + threadLength + remainder - 1;
            }
            subTaskList.add(createSubTask(start, end));
        }
    }

    private Task createSubTask(long start, long end) {
        return new Task(task.getSault(), task.getKey(), task.getUri(),
                task.getTarget(), task.getTag(), task.getPriority(), task.getCallback(),
                task.isMultiThreadEnabled(), task.isBreakPointEnabled(), start, end);
    }

    @Override
    public void run() {
        updateThreadName();
        try {
            if (!isNeedResume()) {
                calculateTaskCount();
            } else {
                progressInformer.totalSize = task.totalSize;
            }

            boolean loggingEnable = getSault().isLoggingEnabled();

            for (Task subTask : task.getSubTaskList()) {
                if (loggingEnable) {
                    log(String.format(Locale.US,
                            "Create task. Task={id=%d,finishedSize=%d,totalSize=%d,startPosition=%d,endPosition=%d}",
                            subTask.id, subTask.finishedSize, subTask.totalSize,
                            subTask.getStartPosition(), subTask.getEndPosition()));
                }
                if (subTask.isDone()) {
                    continue;
                }
                TaskHunter taskHunter = new SaultDefaultTaskHunter(task.getSault(), dispatcher, subTask,
                        downloader, this, subTask.getStartPosition(), subTask.getEndPosition());

                taskHunterList.add(taskHunter);
                Future future = dispatcher.submit(taskHunter);
                taskHunter.setFuture(future);
            }
        } catch (Exception e) {
            log(e.getMessage(), e);
            setException(e);
        } finally {
            currentThread().setName(THREAD_IDLE_NAME);
        }
    }

    @Override
    public boolean cancel() {
        for (TaskHunter taskHunter : taskHunterList) {
            if (!taskHunter.cancel()) return false;
        }
        return super.cancel();
    }

}
