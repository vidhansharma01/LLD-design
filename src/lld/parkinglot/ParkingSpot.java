package lld.parkinglot;

import lld.parkinglot.factorydesign.Vehicle;
import lld.parkinglot.factorydesign.VehicleType;

public class ParkingSpot {
    int id;
    boolean isEmpty;
    Vehicle vehicle;
    VehicleType spotType;

    ParkingSpot(int id, VehicleType spotType) {
        this.id = id;
        this.isEmpty = true;
        this.vehicle = null;
        this.spotType = spotType;
    }

    public void parkVehicle(Vehicle v) {
        this.vehicle = v;
        this.isEmpty = false;
    }

    public void removeVehicle() {
        this.vehicle = null;
        this.isEmpty = true;
    }

    public boolean isAvailable(){
        return isEmpty;
    }
    public VehicleType getSpotType(){
        return spotType;
    }
}
