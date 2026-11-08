package stub;

import com.extantape.wildernessregen.api.KeepProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Test stand-in for a plugin such as Lantern Graves. With -Dkeepstub.chunk=x,z it reports that chunk as kept;
 * with -Dkeepstub.silent=true it registers nothing, like a graves plugin that doesn't report.
 */
public final class KeepStub extends JavaPlugin {
    @Override
    public void onEnable() {
        if (Boolean.getBoolean("keepstub.silent")) return;
        String[] c = System.getProperty("keepstub.chunk", "0,0").split(",");
        int x = Integer.parseInt(c[0]), z = Integer.parseInt(c[1]);
        getServer().getServicesManager().register(KeepProvider.class,
                world -> List.of(new KeepProvider.Keep(x, z, "an active grave")), this, ServicePriority.Normal);
    }
}
