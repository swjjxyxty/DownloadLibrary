package com.bestxty.sault.dispatcher;

import com.bestxty.sault.task.SaultTask;

/**
 * @author 姜泰阳
 *         Created by 姜泰阳 on 2017/10/12.
 */

public interface TaskRequestEventDispatcher extends EventDispatcher {

    void dispatchSaultTaskSubmitRequest(SaultTask task);

    void dispatchSaultTaskPauseRequest(SaultTask task);

    void dispatchSaultTaskResumeRequest(SaultTask task);

    void dispatchSaultTaskCancelRequest(SaultTask task);

}
