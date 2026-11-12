package stub;

import com.extantape.rewild.api.KeepProvider;
import org.bukkit.World;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;

/**
 * Test stand-in for a plugin such as LanternGraves. With -Dkeepstub.chunk=x,z it reports that chunk as kept whole;
 * with -Dkeepstub.area=x1,y1,z1,x2,y2,z2 it reports that block box; with -Dkeepstub.silent=true it registers nothing,
 * like a graves plugin that doesn't report.
 */
public final class KeepStub extends JavaPlugin {
    @Override
    public void onEnable() {
        if (Boolean.getBoolean("keepstub.silent")) return;
        String[] c = System.getProperty("keepstub.chunk", "99999,99999").split(",");
        int x = Integer.parseInt(c[0]), z = Integer.parseInt(c[1]);
        String a = System.getProperty("keepstub.area");
        List<KeepProvider.Area> areas;
        if (a == null) {
            areas = List.of();
        } else {
            String[] p = a.split(",");
            areas = List.of(new KeepProvider.Area(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), "a grave"));
        }
        getServer().getServicesManager().register(KeepProvider.class, new KeepProvider() {
            @Override
            public Collection<Keep> keeps(World world) {
                return List.of(new Keep(x, z, "an active grave"));
            }

            @Override
            public Collection<Area> areas(World world) {
                return areas;
            }
        }, this, ServicePriority.Normal);
    }
}
