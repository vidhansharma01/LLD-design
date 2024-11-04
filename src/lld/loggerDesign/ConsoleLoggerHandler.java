package lld.loggerDesign;

public class ConsoleLoggerHandler extends LoggerHandler{
    @Override
    public void handle(String msg, LogLevel level) {
        if (level == LogLevel.INFO || level == LogLevel.DEBUG) {
            System.out.println("Console: " + level + ": " + msg);
        }
        if (nextLoggerHandler != null) {
            nextLoggerHandler.handle(msg, level);
        }
    }
}
