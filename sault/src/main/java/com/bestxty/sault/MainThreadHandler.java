package com.bestxty.sault;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * @author 姜泰阳
 *         Created by 姜泰阳 on 2017/10/12.
 */

public class MainThreadHandler extends Handler implements SaultTaskEventHandler {

    public MainThreadHandler(Looper looper) {
        super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case SAULT_TASK_START:
                handleSaultTaskStart(((SaultTask) msg.obj));
                break;
            case SAULT_TASK_PAUSE:
                handleSaultTaskPause(((SaultTask) msg.obj));
                break;
            case SAULT_TASK_RESUME:
                handleSaultTaskResume(((SaultTask) msg.obj));
                break;
            case SAULT_TASK_CANCEL:
                handleSaultTaskCancel(((SaultTask) msg.obj));
                break;
            case SAULT_TASK_COMPLETE:
                handleSaultTaskComplete(((SaultTask) msg.obj));
                break;
            case SAULT_TASK_PROGRESS:
                handleSaultTaskProgress(((SaultTask) msg.obj));
                break;
            case SAULT_TASK_EXCEPTION:
                handleSaultTaskException(((SaultTask) msg.obj));
                break;
        }
    }

    @Override
    public void handleSaultTaskStart(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        callback.onEvent(task.getTag(), com.bestxty.sault.Callback.EVENT_START);
    }

    @Override
    public void handleSaultTaskPause(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        callback.onEvent(task.getTag(), com.bestxty.sault.Callback.EVENT_PAUSE);
    }

    @Override
    public void handleSaultTaskResume(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        callback.onEvent(task.getTag(), com.bestxty.sault.Callback.EVENT_RESUME);
    }

    @Override
    public void handleSaultTaskCancel(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        callback.onEvent(task.getTag(), com.bestxty.sault.Callback.EVENT_CANCEL);
    }

    @Override
    public void handleSaultTaskComplete(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        callback.onEvent(task.getTag(), com.bestxty.sault.Callback.EVENT_COMPLETE);
    }

    @Override
    public void handleSaultTaskProgress(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        SaultTask.Progress progress = task.getProgress();
        callback.onProgress(task.getTag(), progress.getTotalSize(), progress.getFinishedSize());
    }

    @Override
    public void handleSaultTaskException(SaultTask task) {
        com.bestxty.sault.Callback callback = task.getCallback();
        if (callback == null) {
            return;
        }
        if (task instanceof ExceptionSaultTask) {
            Exception exception = ((ExceptionSaultTask) task).getException();
            callback.onError(new SaultException(exception));
        }
    }
}
