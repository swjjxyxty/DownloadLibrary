package com.bestxty.sault.dispatcher;

import android.os.Handler;

import com.bestxty.sault.dispatcher.handler.InternalEventDispatcherHandler;
import com.bestxty.sault.hunter.TaskHunter;
import com.bestxty.sault.task.SaultTask;

import javax.inject.Inject;
import javax.inject.Singleton;

import static com.bestxty.sault.handler.HunterEventHandler.HUNTER_EXCEPTION;
import static com.bestxty.sault.handler.HunterEventHandler.HUNTER_FAILED;
import static com.bestxty.sault.handler.HunterEventHandler.HUNTER_FINISH;
import static com.bestxty.sault.handler.HunterEventHandler.HUNTER_RETRY;
import static com.bestxty.sault.handler.HunterEventHandler.HUNTER_START;
import static com.bestxty.sault.handler.TaskRequestEventHandler.TASK_CANCEL_REQUEST;
import static com.bestxty.sault.handler.TaskRequestEventHandler.TASK_PAUSE_REQUEST;
import static com.bestxty.sault.handler.TaskRequestEventHandler.TASK_RESUME_REQUEST;
import static com.bestxty.sault.handler.TaskRequestEventHandler.TASK_SUBMIT_REQUEST;

/**
 * @author 姜泰阳
 *         Created by 姜泰阳 on 2017/10/17.
 */
@Singleton
public class DefaultHunterEventDispatcher implements HunterEventDispatcher, TaskRequestEventDispatcher {

    private final Handler hunterHandler;
    private final DispatcherThread dispatcherThread;

    @Inject
    DefaultHunterEventDispatcher(DispatcherThread dispatcherThread,
                                 InternalEventDispatcherHandler internalEventDispatcherHandler) {
        this.dispatcherThread = dispatcherThread;
        hunterHandler = internalEventDispatcherHandler;
    }

    @Override
    public void dispatchHunterStart(TaskHunter hunter) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(HUNTER_START, hunter));
    }

    @Override
    public void dispatchHunterRetry(TaskHunter hunter) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(HUNTER_RETRY, hunter));
    }

    @Override
    public void dispatchHunterException(TaskHunter hunter) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(HUNTER_EXCEPTION, hunter));
    }

    @Override
    public void dispatchHunterFinish(TaskHunter hunter) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(HUNTER_FINISH, hunter));
    }

    @Override
    public void dispatchHunterFailed(TaskHunter hunter) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(HUNTER_FAILED, hunter));
    }


    @Override
    public void dispatchSaultTaskSubmitRequest(SaultTask task) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(TASK_SUBMIT_REQUEST, task));
    }

    @Override
    public void dispatchSaultTaskPauseRequest(SaultTask task) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(TASK_PAUSE_REQUEST, task));
    }

    @Override
    public void dispatchSaultTaskResumeRequest(SaultTask task) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(TASK_RESUME_REQUEST, task));
    }

    @Override
    public void dispatchSaultTaskCancelRequest(SaultTask task) {
        hunterHandler.sendMessage(hunterHandler.obtainMessage(TASK_CANCEL_REQUEST, task));
    }


    @Override
    public void shutdown() {
        hunterHandler.removeCallbacksAndMessages(null);
        dispatcherThread.quit();
    }
}