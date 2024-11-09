package lld.proxy;

public class Main {
    public static void main(String args[]) throws Exception {
        try {
            EmployeeDao employeeDao = new EmployeeDaoProxy();
            employeeDao.create("ADMIN", new Employee("1", "vidhan"));
            System.out.println("operation successful");
        }catch(Exception e){
            System.out.println(e.getMessage());
        }
    }
}
