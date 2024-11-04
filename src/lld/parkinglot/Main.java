package lld.parkinglot;

import lld.parkinglot.factorydesign.Bike;
import lld.parkinglot.factorydesign.Car;
import lld.parkinglot.factorydesign.Vehicle;
import lld.parkinglot.factorydesign.VehicleType;
import lld.parkinglot.strategydesign.FirstComeFirstServeStrategy;
import lld.parkinglot.strategydesign.ParkingStrategy;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String args[]){
        List<ParkingSpot> spots = new ArrayList<>();
        spots.add(new ParkingSpot(1, VehicleType.BIKE));
        spots.add(new ParkingSpot(2, VehicleType.CAR));
        spots.add(new ParkingSpot(3, VehicleType.TRUCK));

        Vehicle car = new Car("ABC123");
        Vehicle bike = new Bike("XYZ789");

        ParkingStrategy strategy = new FirstComeFirstServeStrategy();
        ParkingLot parkingLot = new ParkingLot(strategy, spots);

        ParkingTicket ticket1 = parkingLot.parkVehicle(car);
        ParkingTicket ticket2 = parkingLot.parkVehicle(bike);

        parkingLot.getStatus();

        System.out.println("---------------------------");

        // Unpark a vehicle
        parkingLot.unParkVehicle(ticket1);

        // Display parking lot status again
        parkingLot.getStatus();

        System.out.println("---------------------------");

        parkingLot.unParkVehicle(ticket2);

        parkingLot.getStatus();
    }
}
