package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class TtsSettings extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(length = 255)
    public String voice;

    public Double speedMultiplier;

    public double effectiveSpeedMultiplier() {
        if (speedMultiplier == null || speedMultiplier <= 0) {
            return 1.1d;
        }
        return speedMultiplier;
    }

    public boolean speed11Selected() {
        return Double.compare(effectiveSpeedMultiplier(), 1.1d) == 0;
    }

    public boolean speed12Selected() {
        return Double.compare(effectiveSpeedMultiplier(), 1.2d) == 0;
    }

    public boolean speed13Selected() {
        return Double.compare(effectiveSpeedMultiplier(), 1.3d) == 0;
    }

    public boolean speed15Selected() {
        return Double.compare(effectiveSpeedMultiplier(), 1.5d) == 0;
    }
}
