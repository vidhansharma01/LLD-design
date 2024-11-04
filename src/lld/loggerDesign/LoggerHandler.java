package lld.loggerDesign;

public abstract class LoggerHandler {
    protected LoggerHandler nextLoggerHandler;

    public void setNextHandler(LoggerHandler loggerHandler){
        nextLoggerHandler = loggerHandler;
    }
    public abstract void handle(String msg, LogLevel logLevel);
}
