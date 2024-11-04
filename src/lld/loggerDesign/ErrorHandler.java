package lld.loggerDesign;

public class ErrorHandler extends LoggerHandler{

    @Override
    public void handle(String msg, LogLevel level) {
        if (level == LogLevel.ERROR) {
            System.err.println("Error Log: " + msg);
        }
        if (nextLoggerHandler != null) {
            nextLoggerHandler.handle(msg, level);
        }
    }
}
