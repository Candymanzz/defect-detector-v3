package com.example.iml.orchestrator.integration.hikrobot.mvio;

import com.sun.jna.Structure;

import java.util.List;

/** MV_IO_INPUT_LEVEL — уровни DI1..DI8 (см. MvIOInterfaceBoxDefine.h). */
public class MvIoInputLevel extends Structure {

    public byte nPortNumber;
    public byte nLevel0;
    public byte nLevel1;
    public byte nLevel2;
    public byte nLevel3;
    public byte nLevel4;
    public byte nLevel5;
    public byte nLevel6;
    public byte nLevel7;
    public int[] nReserved = new int[8];

    public MvIoInputLevel() {
        super(ALIGN_NONE);
    }

    byte levelForPort(int portOneBased) {
        return switch (portOneBased) {
            case 1 -> nLevel0;
            case 2 -> nLevel1;
            case 3 -> nLevel2;
            case 4 -> nLevel3;
            case 5 -> nLevel4;
            case 6 -> nLevel5;
            case 7 -> nLevel6;
            case 8 -> nLevel7;
            default -> throw new IllegalArgumentException("DI port must be 1..8, got " + portOneBased);
        };
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of(
                "nPortNumber",
                "nLevel0",
                "nLevel1",
                "nLevel2",
                "nLevel3",
                "nLevel4",
                "nLevel5",
                "nLevel6",
                "nLevel7",
                "nReserved"
        );
    }
}
