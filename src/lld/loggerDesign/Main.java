package lld.loggerDesign;

//Logger LLD design pattern - Chain of Responsibility
public class Main {
    public static void main(String args[]){
        LoggerHandler consoleHanlder = new ConsoleLoggerHandler();
        LoggerHandler errorHandler = new ErrorHandler();
        consoleHanlder.setNextHandler(errorHandler);
        consoleHanlder.handle("Hi", LogLevel.ERROR);
    }
}
