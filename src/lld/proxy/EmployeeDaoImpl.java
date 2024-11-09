package lld.proxy;

public class EmployeeDaoImpl implements EmployeeDao{
    @Override
    public void create(String client, Employee obj) {
        System.out.println("create new employee");
    }

    @Override
    public void delete(String client, int employeeId) {
        System.out.println("delete employee");
    }

    @Override
    public Employee get(String client, int employeeId) {
        System.out.println("fetch employee details");
        return null;
    }
}
