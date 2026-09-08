package com.feng.medical.conversation;

public class ActiveRunConflictException extends RuntimeException {
    public ActiveRunConflictException() { super("当前会话已有正在执行的任务"); }
}
