package lld.parkinglot.factorydesign;

public abstract class Vehicle {
    protected String vehicleNo;
    protected VehicleType vehicleType;

    public Vehicle(String vehicleNo, VehicleType vehicleType) {
        this.vehicleNo = vehicleNo;
        this.vehicleType = vehicleType;
    }

    public String getVehicleNo() {
        return vehicleNo;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }
}
