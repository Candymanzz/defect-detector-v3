package com.example.iml.orchestrator.integration.io.mvs;

import com.sun.jna.Memory;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JnaMvsIoHotpathTest {
    static final class Sdk implements JnaMvsIoDevice.SdkInvoker {
        final List<String> calls = new ArrayList<>();
        String selected; boolean failNextSource;
        public int invoke(String name, Object... args) {
            if (name.equals("MV_CC_SetEnumValueByString")) {
                String node=(String)args[1], value=(String)args[2]; calls.add(node+"="+value);
                if (node.equals("LineSelector")) selected=value;
                else if (node.equals("LineSource") && failNextSource) { failNextSource=false;return -1; }
                return 0;
            }
            if (name.equals("MV_CC_GetBoolValue")) {
                calls.add("read="+selected); ((Memory)args[2]).setByte(0,(byte)1); return 0;
            }
            throw new AssertionError("Unexpected SDK readback on hot path: "+name);
        }
    }
    private static JnaMvsIoDevice connected(Sdk sdk) throws Exception {
        var integration=Map.<String,Object>of("inspection_trigger",Map.of("mvs_io",Map.of("enabled",true,
                        "outputs",Map.of(),"inputs",Map.of(2,Map.of("line","In2"),4,Map.of("line","In4")))),
                "io_reject",Map.of("enabled",true,"mode","hold","groups",Map.of(0,Map.of("port",3),1,Map.of("port",4))));
        var device=new JnaMvsIoDevice(MvsIoConfig.parse(integration),LogManager.getLogger(JnaMvsIoHotpathTest.class),sdk);
        // Exercise the established connection's hot path without loading a physical SDK/device.
        var opened=JnaMvsIoDevice.class.getDeclaredField("opened");opened.setAccessible(true);opened.setBoolean(device,true);
        return device;
    }
    @Test void batchHasOnlyRequiredWritesAndDoesNotRestoreOrReadBackSelector() throws Exception {
        var sdk=new Sdk();var device=connected(sdk);
        device.setOutputs(Map.of(3,false,4,true));
        assertEquals(List.of("LineSelector=Out3","LineSource=Off","LineSelector=Out4","LineSource=On"),sdk.calls);
        sdk.calls.clear();device.setOutputs(Map.of(3,false,4,true));assertTrue(sdk.calls.isEmpty());
        device.setOutput(4,false);assertEquals(List.of("LineSource=Off"),sdk.calls);
        sdk.calls.clear();assertEquals(Map.of(2,true,4,true),device.readInputs());
        assertEquals(List.of("LineSelector=In2","read=In2","LineSelector=In4","read=In4"),sdk.calls);
    }
    @Test void invalidBatchTouchesNoOutputsIncludingWhenFirstPortIsValid() throws Exception {
        var sdk=new Sdk();var device=connected(sdk);
        assertThrows(IllegalArgumentException.class,()->device.setOutputs(Map.of(3,true,5,false)));
        assertTrue(sdk.calls.isEmpty());
    }
    @Test void failedSdkWriteInvalidatesSelectorAndOutputCachesBeforeRetry() throws Exception {
        var sdk=new Sdk();var device=connected(sdk);
        device.setOutputs(Map.of(3,false,4,true));sdk.calls.clear();sdk.failNextSource=true;
        assertThrows(IllegalStateException.class,()->device.setOutput(4,false));
        sdk.calls.clear();device.setOutputs(Map.of(3,false,4,true));
        assertEquals(List.of("LineSelector=Out3","LineSource=Off","LineSelector=Out4","LineSource=On"),sdk.calls);
    }
}
