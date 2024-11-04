package lld.parkinglot;

import lld.parkinglot.factorydesign.Vehicle;
import lld.parkinglot.strategydesign.ParkingStrategy;

import java.util.List;
import java.util.UUID;

public class ParkingLot {
    private List<ParkingSpot> parkingSpotList;
    private ParkingStrategy parkingStrategy;

    public ParkingLot(ParkingStrategy parkingStrategy, List<ParkingSpot> parkingSpotList) {
        this.parkingStrategy = parkingStrategy;
        this.parkingSpotList = parkingSpotList;
    }

    public ParkingTicket parkVehicle(Vehicle vehicle){
        ParkingSpot parkingSpot = parkingStrategy.findSpot(vehicle, parkingSpotList);
        if (parkingSpot != null){
            parkingSpot.parkVehicle(vehicle);
            String ticketId = UUID.randomUUID().toString();
            return new ParkingTicket(ticketId, vehicle, parkingSpot);
        }
        System.out.println("Parking full for " + vehicle.getVehicleType());
        return null;
    }

    public boolean unParkVehicle(ParkingTicket ticket) {
        ParkingSpot spot = ticket.getParkingSpot();
        spot.removeVehicle();
        return true;
    }

    public void getStatus() {
        for (ParkingSpot spot : parkingSpotList) {
            if (spot.isAvailable())
                System.out.println("Spot " + spot.id + ": " + spot.getSpotType() + " spots available");
            else
                System.out.println("Spot " + spot.id + ": " + spot.getSpotType() + " spots unavailable");
        }
    }
}
