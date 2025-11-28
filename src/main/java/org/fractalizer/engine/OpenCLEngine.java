package org.fractalizer.engine;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL engine for GPU-accelerated fractal computation.
 * Handles device initialization, kernel compilation, and memory management.
 */
public class OpenCLEngine implements AutoCloseable {

    private long platform;
    private long device;
    private long context;
    private long commandQueue;
    private final Map<String, Long> programs = new HashMap<>();
    private final Map<String, Long> kernels = new HashMap<>();

    private CLContextCallback contextCallback;
    private String deviceName;
    private long maxWorkGroupSize;
    private long maxMemAllocSize;

    public OpenCLEngine() {
        initialize();
    }

    private void initialize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer numPlatforms = stack.mallocInt(1);
            checkCLError(clGetPlatformIDs(null, numPlatforms));

            if (numPlatforms.get(0) == 0) {
                throw new RuntimeException("No OpenCL platforms found");
            }

            PointerBuffer platforms = stack.mallocPointer(numPlatforms.get(0));
            checkCLError(clGetPlatformIDs(platforms, (IntBuffer) null));

            // Find GPU device (prefer NVIDIA)
            platform = 0;
            device = 0;

            for (int i = 0; i < numPlatforms.get(0); i++) {
                long plat = platforms.get(i);
                IntBuffer numDevices = stack.mallocInt(1);

                int err = clGetDeviceIDs(plat, CL_DEVICE_TYPE_GPU, null, numDevices);
                if (err == CL_SUCCESS && numDevices.get(0) > 0) {
                    PointerBuffer devices = stack.mallocPointer(numDevices.get(0));
                    checkCLError(clGetDeviceIDs(plat, CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null));

                    platform = plat;
                    device = devices.get(0);
                    break;
                }
            }

            if (device == 0) {
                throw new RuntimeException("No GPU device found");
            }

            // Get device info
            deviceName = getDeviceInfoString(device, CL_DEVICE_NAME);

            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            clGetDeviceInfo(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeBuffer, null);
            maxWorkGroupSize = sizeBuffer.get(0);

            clGetDeviceInfo(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeBuffer, null);
            maxMemAllocSize = sizeBuffer.get(0);

            System.out.println("OpenCL Device: " + deviceName);
            System.out.println("Max Work Group Size: " + maxWorkGroupSize);
            System.out.println("Max Memory Allocation: " + (maxMemAllocSize / 1024 / 1024) + " MB");

            // Create context
            contextCallback = CLContextCallback.create((errinfo, private_info, cb, user_data) -> {
                System.err.println("OpenCL Context Error: " + memUTF8(errinfo));
            });

            PointerBuffer contextProps = stack.mallocPointer(3);
            contextProps.put(0, CL_CONTEXT_PLATFORM);
            contextProps.put(1, platform);
            contextProps.put(2, 0);

            IntBuffer errBuffer = stack.mallocInt(1);
            context = clCreateContext(contextProps, device, contextCallback, NULL, errBuffer);
            checkCLError(errBuffer.get(0));

            // Create command queue
            commandQueue = clCreateCommandQueue(context, device, 0, errBuffer);
            checkCLError(errBuffer.get(0));
        }
    }

    /**
     * Load and compile an OpenCL kernel from resources.
     */
    public void loadKernel(String name, String resourcePath, String functionName) throws IOException {
        String source = loadResource(resourcePath);

        try (MemoryStack stack = stackPush()) {
            IntBuffer errBuffer = stack.mallocInt(1);

            long program = clCreateProgramWithSource(context, source, errBuffer);
            checkCLError(errBuffer.get(0));

            int buildErr = clBuildProgram(program, device, "", null, NULL);
            if (buildErr != CL_SUCCESS) {
                String buildLog = getProgramBuildInfo(program, device);
                throw new RuntimeException("OpenCL build error:\n" + buildLog);
            }

            long kernel = clCreateKernel(program, functionName, errBuffer);
            checkCLError(errBuffer.get(0));

            programs.put(name, program);
            kernels.put(name, kernel);
        }
    }

    /**
     * Create a buffer on the GPU.
     */
    public long createBuffer(long flags, long size) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer errBuffer = stack.mallocInt(1);
            long buffer = clCreateBuffer(context, flags, size, errBuffer);
            checkCLError(errBuffer.get(0));
            return buffer;
        }
    }

    /**
     * Create a buffer initialized with data.
     */
    public long createBuffer(long flags, ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer errBuffer = stack.mallocInt(1);
            long buffer = clCreateBuffer(context, flags, data, errBuffer);
            checkCLError(errBuffer.get(0));
            return buffer;
        }
    }

    /**
     * Write data to a GPU buffer.
     */
    public void writeBuffer(long buffer, ByteBuffer data) {
        checkCLError(clEnqueueWriteBuffer(commandQueue, buffer, true, 0, data, null, null));
    }

    /**
     * Read data from a GPU buffer.
     */
    public void readBuffer(long buffer, ByteBuffer data) {
        checkCLError(clEnqueueReadBuffer(commandQueue, buffer, true, 0, data, null, null));
    }

    /**
     * Set kernel argument (buffer).
     */
    public void setKernelArg(String kernelName, int index, long buffer) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.pointers(buffer)));
        }
    }

    /**
     * Set kernel argument (int).
     */
    public void setKernelArgInt(String kernelName, int index, int value) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.ints(value)));
        }
    }

    /**
     * Set kernel argument (float).
     */
    public void setKernelArgFloat(String kernelName, int index, float value) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.floats(value)));
        }
    }

    /**
     * Set kernel argument (float array/vector).
     */
    public void setKernelArgFloats(String kernelName, int index, float... values) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.floats(values)));
        }
    }

    /**
     * Execute a kernel with 2D work size (for image rendering).
     */
    public void executeKernel2D(String kernelName, int width, int height) {
        long kernel = kernels.get(kernelName);

        try (MemoryStack stack = stackPush()) {
            PointerBuffer globalWorkSize = stack.mallocPointer(2);
            globalWorkSize.put(0, width);
            globalWorkSize.put(1, height);

            // Calculate optimal local work size
            int localX = 16;
            int localY = 16;
            while (localX * localY > maxWorkGroupSize) {
                if (localX > localY) localX /= 2;
                else localY /= 2;
            }

            PointerBuffer localWorkSize = stack.mallocPointer(2);
            localWorkSize.put(0, localX);
            localWorkSize.put(1, localY);

            checkCLError(clEnqueueNDRangeKernel(commandQueue, kernel, 2,
                null, globalWorkSize, localWorkSize, null, null));
        }
    }

    /**
     * Wait for all queued commands to complete.
     */
    public void finish() {
        checkCLError(clFinish(commandQueue));
    }

    /**
     * Release a buffer.
     */
    public void releaseBuffer(long buffer) {
        clReleaseMemObject(buffer);
    }

    public long getDevice() {
        return device;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public long getMaxWorkGroupSize() {
        return maxWorkGroupSize;
    }

    public long getMaxMemAllocSize() {
        return maxMemAllocSize;
    }

    @Override
    public void close() {
        for (long kernel : kernels.values()) {
            clReleaseKernel(kernel);
        }
        for (long program : programs.values()) {
            clReleaseProgram(program);
        }
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);
        if (contextCallback != null) {
            contextCallback.free();
        }
    }

    private String loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String getDeviceInfoString(long device, int param) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, (ByteBuffer) null, sizeBuffer);

            ByteBuffer buffer = stack.malloc((int) sizeBuffer.get(0));
            clGetDeviceInfo(device, param, buffer, null);

            return memUTF8(buffer, (int) sizeBuffer.get(0) - 1);
        }
    }

    private String getProgramBuildInfo(long program, long device) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, sizeBuffer);

            ByteBuffer buffer = stack.malloc((int) sizeBuffer.get(0));
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buffer, null);

            return memUTF8(buffer, (int) sizeBuffer.get(0) - 1);
        }
    }

    private void checkCLError(int error) {
        if (error != CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + error);
        }
    }
}