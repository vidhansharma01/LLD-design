package lld.parkinglot.factorydesign;

public class VehicleFactory {
    public static Vehicle createVehicle(String type, String licenseNumber) {
        switch (type.toUpperCase()) {
            case "CAR":
                return new Car(licenseNumber);
            case "BIKE":
                return new Bike(licenseNumber);
            case "TRUCK":
                return new Truck(licenseNumber);
            default:
                throw new IllegalArgumentException("Unknown vehicle type");
        }
    }
}
