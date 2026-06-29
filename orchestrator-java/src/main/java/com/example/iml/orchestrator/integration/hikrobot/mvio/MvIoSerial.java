package com.example.iml.orchestrator.integration.hikrobot.mvio;

import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** MV_IO_SERIAL — открытие COM-порта IO SDK (Hikrobot MV_VC_VB_VT_IO_SDK). */
public class MvIoSerial extends Structure {

    public byte[] strComName = new byte[64];
    public int[] nReserved = new int[8];

    public MvIoSerial() {
        super(ALIGN_NONE);
    }

    public MvIoSerial(String comPort) {
        this();
        setComPort(comPort);
    }

    public void setComPort(String comPort) {
        Arrays.fill(strComName, (byte) 0);
        if (comPort == null || comPort.isBlank()) {
            return;
        }
        byte[] bytes = comPort.trim().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, strComName, 0, Math.min(bytes.length, strComName.length - 1));
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("strComName", "nReserved");
    }
}
