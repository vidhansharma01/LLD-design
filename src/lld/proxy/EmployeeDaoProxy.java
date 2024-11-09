package lld.proxy;

public class EmployeeDaoProxy implements EmployeeDao{
    EmployeeDao employeeDao;

    public EmployeeDaoProxy() {
        this.employeeDao = new EmployeeDaoImpl();
    }

    @Override
    public void create(String client, Employee obj) throws Exception {
        if (client.equals("ADMIN")){
            employeeDao.create(client, obj);
            return;
        }
        throw new Exception("Access denied");
    }

    @Override
    public void delete(String client, int employeeId) throws Exception {
        if (client.equals("ADMIN")){
            employeeDao.delete(client, employeeId);
            return;
        }
        throw new Exception("Access denied");
    }

    @Override
    public Employee get(String client, int employeeId) throws Exception {
        if (client.equals("ADMIN") || client.equals("USER")){
            return employeeDao.get(client, employeeId);
        }
        throw new Exception("Access denied");
    }
}
