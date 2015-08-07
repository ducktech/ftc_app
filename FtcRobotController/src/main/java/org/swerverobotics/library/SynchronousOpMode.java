package org.swerverobotics.library;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import android.util.SparseArray;
import junit.framework.Assert;
import com.qualcomm.ftcrobotcontroller.BuildConfig;
import com.qualcomm.robotcore.hardware.*;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import org.swerverobotics.library.exceptions.*;
import org.swerverobotics.library.interfaces.*;
import org.swerverobotics.library.internal.*;

/**
 * SynchronousOpMode is a base class that can be derived from in order to
 * write op modes that can be coded in a linear, synchronous programming style.
 *
 * Extend this class and implement the main() method to add your own code.
 */
public abstract class SynchronousOpMode extends OpMode implements IThunker
    {
    //----------------------------------------------------------------------------------------------
    // Types
    //----------------------------------------------------------------------------------------------

    private class ActionQueueAndHistory
        {
        ConcurrentLinkedQueue<IAction>  queue   = new ConcurrentLinkedQueue<IAction>(); // could be simpler queue
        SparseArray<Boolean>            history = new SparseArray<Boolean>(); 
        
        synchronized void clear()
            {
            this.queue = new ConcurrentLinkedQueue<IAction>();
            this.clearHistory();
            }
        
        synchronized void clearHistory()
            {
            this.history = new SparseArray<Boolean>();
            this.onChanged();
            }
        
        synchronized void add(IAction action)
            {
            this.queue.add(action);
            this.onChanged();
            }
        
        synchronized IAction poll()
            {
            IAction result = this.queue.poll();
            if (result != null)
                {
                if (result instanceof IActionKeyed)
                    {
                    IActionKeyed keyed = (IActionKeyed)result;
                    this.setActionKey(keyed.getActionKey());
                    }
                this.onChanged();
                }
            return result;
            }
        
        synchronized void setActionKey(int actionKey)
            {
            if (actionKey != ThunkBase.nullActionKey)
                {
                this.history.setValueAt(actionKey, true);
                this.onChanged();
                }
            }
        
        synchronized boolean containsActionKey(int thunkKey)
            {
            // Is the key present in pending stuff?
            for (IAction action : this.queue)
                {
                if (action instanceof IActionKeyed)
                    {
                    IActionKeyed keyed = (IActionKeyed)action;
                    if (keyed.getActionKey() == thunkKey)
                        {
                        return true;
                        }
                    }
                }
            
            // Is the key present in our history?
            if (this.history.get(thunkKey, false))
                {
                return true;
                }
            
            // Not present
            return false;
            }
        
        private void onChanged()
            {
            this.notifyAll();
            }
        }

    //----------------------------------------------------------------------------------------------
    // State
    //----------------------------------------------------------------------------------------------

    private         Thread                  loopThread;
    private         Thread                  mainThread;
    private         RuntimeException        exceptionThrownOnMainThread;
    private volatile boolean                started;
    private volatile boolean                stopRequested;
    private         int                     msWaitForGracefulMainThreadTermination = 250;     
    private         ActionQueueAndHistory   actionQueueAndHistory = new ActionQueueAndHistory();
    private         AtomicBoolean           gamePadStateChanged = new AtomicBoolean(false);
    private final   Object                  loopLock = new Object();
    private final   SparseArray<IAction>    singletonLoopActions = new SparseArray<IAction>();
    private static  AtomicInteger           singletonKey = new AtomicInteger(0);

    /**
     * Advanced: unthunkedHardwareMap contains the original hardware map provided
     * in OpMode before it was replaced with a version that does internal. unthunkedTelemetry
     * is similar, but for telemetry instead of hardware
     */
    public HardwareMap unthunkedHardwareMap = null;

    /**
     * The game pad variables are redeclared here so as to hide those in our OpMode superclass
     * as the latter may be updated by robot controller runtime at arbitrary times and in a manner 
     * which is not synchronized with processing on a synchronous thread. We take pains to ensure 
     * that the variables declared here do not suffer from that problem.
     */
    public IGamepad gamepad1 = null;
    public IGamepad gamepad2 = null;

    /**
     * As with game pads, we hid the 'telemetry' variable of the super class and replace it
     * with one that can work from synchronous threads.
     */
    public TelemetryDashboardAndLog telemetry;

    /**
     * The number of nanoseconds in a millisecond.
     */
    public static final long NANO_TO_MILLI = 1000000;

    /**
     * Advanced: 'msLoopDwellMax' is the (soft) maximum number of milliseconds that
     * our loop() implementation will spend in any one call before returning.
     */
    public long msLoopDwellMax = 20;

    /**
     * Advanced: loopDwellCheckInterval is the number of thunks we will execute in loop()
     * before checking whether we've exceeded msLoopDwellMax.
     */
    public int loopDwellCheckCount = 5;

    /**
     * Advanced: the number of times the loop thread has been called
     */
    public AtomicInteger loopCount = new AtomicInteger(0);

    //----------------------------------------------------------------------------------------------
    // Construction
    //----------------------------------------------------------------------------------------------

    public SynchronousOpMode()
        {
        }

    //----------------------------------------------------------------------------------------------
    // User code, and stuff commonly used by user code
    //----------------------------------------------------------------------------------------------

    public void waitForStart() throws InterruptedException
        {
        synchronized (this.loopLock)
            {
            do
                {
                this.loopLock.wait();
                }
            while (!this.started); // avoid spurious wakeups
            }
        }
    
    /**
     * Central idea: implement main() (in a subclass) to contain your robot logic!
     */
    protected abstract void main() throws InterruptedException;

    /**
     * Answer as to whether there's (probably) any state different in any of the game pads
     * since the last time that this method was called
     */
    public final boolean newGamePadInputAvailable()
        {
        // We *wish* there was a way that we could hook or get a callback from the
        // incoming gamepad change messages, but, alas, at present we can find no
        // way of doing that.
        //
        return this.gamePadStateChanged.getAndSet(false);
        }

    /**
     * Similar to newGamePadInputAvailable(), but doesn't auto-reset the state when called
     *
     * @see #newGamePadInputAvailable()
     */
    public final boolean isNewGamePadInputAvailable()
        {
        return this.gamePadStateChanged.get();
        }

    /**
     * Put the current thread to sleep for a bit as it has nothing better to do.
     *
     * idle(), which is called on a synchronous thread, never on the loop() thread, causes the
     * synchronous thread to go to sleep until it is likely that the robot controller runtime
     * has gotten back in touch with us (by calling loop() again) and thus the state reported
     * by our various hardware devices and sensors might be different than what it was.
     *
     * One can use this method when you have nothing better to do until the underlying
     * robot controller runtime gets back in touch with us. Thread.yield() has similar effects, but
     * idle() / synchronousThreadIdle() is more efficient.
     */
    public final void idle() throws InterruptedException
        {
        synchronized (this.loopLock)
            {
            // If new input has arrived since anyone last looked, then let our caller process that
            if (this.isNewGamePadInputAvailable())
                return;
            
            // Otherwise, we know there's nothing to do until at least the next loop() call.
            // The trouble is, it's hard to know when that is. We might be running here 
            // *immediately* before loop() is about to run. Looking at loop counts could allow
            // us to guarantee that we wait at least one whole cycle, yes, but that's overkill,
            // that's not what we're looking for. So instead, we just wait until loop() pings us
            // it the bottom of it's cycle, which may be a bit less than a whole loop(), but is
            // the reasonable compromise.
            this.loopLock.wait();
            }
        }

    /**
     * Idles the current thread until stimulated by the robot controller runtime
     *
     * @see #idle()
     */
    public static void synchronousThreadIdle() throws InterruptedException
        {
        getSynchronousOpMode().idle();
        }

    /**
     * Answer as to whether this opMode is active and the robot should continue onwards. If the
     * opMode is not active, synchronous threads should terminate at their earliest convenience.
     */
    public final boolean opModeIsActive()
        {
        return this.started() && !this.stopRequested();
        }

    /**
     * Has the opMode been started?
     */
    public final boolean started()
        {
        return this.started;
        }
    
    /**
     * Has the the stopping of the opMode been requested?
     */
    public final boolean stopRequested()
        {
        return this.stopRequested || Thread.currentThread().isInterrupted();
        }

    //----------------------------------------------------------------------------------------------
    // start(), loop(), and stop()
    //----------------------------------------------------------------------------------------------

    /**
     * An instance of Runner is called on the loop() thread in order to start up the main() thread.
     */
    private class Runner implements Runnable
        {
        /**
         * The run method calls the synchronous main() method to finally
         * actually run the user's code.
         */
        public final void run()
            {
            // Note that this op mode is the thing on this thread that can thunk back to the loop thread
            SynchronousOpMode.this.setThreadThunker();

            try
                {
                SynchronousOpMode.this.main();
                }
            catch (InterruptedException ignored) { }
            catch (RuntimeInterruptedException ignored)
                {
                // If the main() method itself doesn't catch the interrupt, at least
                // we will do so here. The whole point of such interrupts is to
                // get the thread to shut down, which we are about to do here by
                // falling off the end of run().
                }
            catch (RuntimeException e)
                {
                // Remember the exception so we can (re)throw it back on over in loop.
                // TODO: is this really the best idea? Certainly should test it thoroughly.
                SynchronousOpMode.this.exceptionThrownOnMainThread = e;
                }
            }
        }

    /**
     * The robot controller runtime calls init(), once, to initialized everything
     */
    @Override public final void init()
        {
        // Call the subclass hook in case they might want to do something interesting
        this.preInitHook();

        // Replace the op mode's hardware map variable with one whose contained
        // object implementations will thunk over to the loop thread as they need to.
        this.unthunkedHardwareMap = super.hardwareMap;
        this.hardwareMap = createThunkedHardwareMap(this.unthunkedHardwareMap);

        // Similarly replace the telemetry variable
        this.telemetry = new TelemetryDashboardAndLog(super.telemetry);

        // Remember who the loop thread is so that we know whom to communicate with from a 
        // synchronous thread. Note: we ASSUME here that start(), loop(), and stop()
        // all run on the same thread.
        loopThread = Thread.currentThread();

        // Paranoia: clear any state that may just perhaps be lingering
        this.clearSingletons();
        this.actionQueueAndHistory.clear();

        // We're being asked to start, not stop
        this.started = false;
        this.stopRequested = false;
        this.loopCount = new AtomicInteger(0);
        this.exceptionThrownOnMainThread = null;

        // Create the main thread and start it up and going!
        this.mainThread = new Thread(new Runner());
        this.mainThread.start();

        // Call the subclass hook in case they might want to do something interesting
        this.postInitHook();
        }

    /**
     * start() is called when the autonomous or the teleop mode begins: the robot
     * should start moving!
     */
    @Override public final void start()
        {
        // Call the subclass hook in case they might want to do something interesting
        this.preStartHook();
        
        synchronized (this.loopLock)
            {
            this.started = true;
            this.loopLock.notifyAll();
            }

        // Call the subclass hook in case they might want to do something interesting
        this.postStartHook();
        }
    
    /**
     * The robot controller runtime calls loop() on a frequent basis, nominally every
     * 20ms or so.
     */
    @Override public final void loop()
        {
        // Call the subclass hook in case they might want to do something interesting
        this.preLoopHook();

        synchronized (this.loopLock)
            {
            this.loopCount.getAndIncrement();
            
            this.actionQueueAndHistory.clearHistory();
            
            // If we had an exception thrown by the main thread, then throw it here. 'Sort
            // of like internal the exceptions.
            if (this.exceptionThrownOnMainThread != null)
                {
                throw this.exceptionThrownOnMainThread;
                }

            // Capture the gamepad states safely so that in a synchronous thread we don't see torn writes
            boolean diff1 = true;
            boolean diff2 = true;
            //
            if (this.gamepad1 == null)
                this.gamepad1 = new ThreadSafeGamepad(super.gamepad1);
            else
                diff1 = ((IGamepadInternal)this.gamepad1).updateGamepad(super.gamepad1);
            //
            if (this.gamepad2 == null)
                this.gamepad2 = new ThreadSafeGamepad(super.gamepad2);
            else
                diff2 = ((IGamepadInternal)this.gamepad2).updateGamepad(super.gamepad2);
            //
            this.gamePadStateChanged.compareAndSet(false, diff1 || diff2);

            // Call the subclass hook in case they might want to do something interesting
            this.midLoopHook();

            // Start measuring time so we don't spend too long here in loop(). That might
            // happen if we got flooded with a bevy of non-waiting actions and we didn't have
            // this check here.
            long nanotimeStart = System.nanoTime();
            long nanotimeMax   = nanotimeStart + this.msLoopDwellMax * NANO_TO_MILLI;

            // Execute any actions we've been asked to execute here on the loop thread
            for (int i = 1; ; i++)
                {
                // Get the next work item in the queue. Get out of here if there isn't any more work.
                IAction action;
                synchronized (this.actionQueueAndHistory)
                    {
                    action = this.actionQueueAndHistory.poll();
                    if (null == action)
                        break;
                    }

                // Execute the work that needs to be done on the loop thread
                action.doAction();

                // Periodically check whether we've run long enough for this loop() call.
                if (i % this.loopDwellCheckCount == 0)
                    {
                    if (System.nanoTime() >= nanotimeMax)
                        break;
                    }
                }

            // Dig out and execute any of our singleton actions.
            ArrayList<IAction> actions = this.snarfSingletons();
            for (IAction action : actions)
                {
                action.doAction();
                }

            // Tell people that this loop cycle is complete
            this.loopLock.notifyAll();
            }

        // Call the subclass hook in case they might want to do something interesting
        this.postLoopHook();
        }
    
    /**
     * Wait until we encounter a loop() cycle that doesn't (yet) contain any actions which
     * are also thunks and whose key is the one indicated.
     */
    public void waitForLoopCycleEmptyOfActionKey(int actionKey) throws InterruptedException
        {
        synchronized (this.actionQueueAndHistory)
            {
            while (this.actionQueueAndHistory.containsActionKey(actionKey))
                {
                this.actionQueueAndHistory.wait();
                }
            }
        }
    public static void synchronousThreadWaitForLoopCycleEmptyOfActionKey(int actionKey) throws InterruptedException
        {
        SynchronousOpMode.getSynchronousOpMode().waitForLoopCycleEmptyOfActionKey(actionKey);
        }

    /**
     * The robot controller runtime calls stop() to shut down the OpMode. 
     *
     * We take steps as best as is possible to ensure that the main() thread is terminated
     * before this call returns.
     */
    @Override public final void stop()
        {
        // Call the subclass hook in case they might want to do something interesting
        this.preStopHook();

        // Next time synchronous threads ask, yes, we do want to stop
        this.stopRequested = true;

        // Notify the MainThread() method that we wish it to stop what it's doing, clean
        // up, and return.
        this.mainThread.interrupt();

        // Wait a while until the thread is no longer alive. If he doesn't clear out
        // in a reasonable amount of time, then just give up on him.
        try {
            this.mainThread.join(this.msWaitForGracefulMainThreadTermination);
            }
        catch (InterruptedException ignored) { }
        
        // Reset for next time
        this.started = false;
        this.stopRequested = false;

        // Call the subclass hook in case they might want to do something interesting
        this.postStopHook();
        }
    
    //----------------------------------------------------------------------------------------------
    // Advanced: loop thread subclass hooks
    //----------------------------------------------------------------------------------------------

    /**
     * Advanced: the various 'hook' calls calls preInitHook(), postInitHook(), preLoopHook(), 
     * etc are hooks that advanced users might want to override in their subclasses to something
     * interesting.
     *
     * The 'pre' and 'post' variations are called at the beginning and the end of their respective
     * methods, while midLoopHook() is called in loop() after variable state (e.g. gamepads) has
     * been established.
     */
    protected void preInitHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void postInitHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void preStartHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void postStartHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void preLoopHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void midLoopHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void postLoopHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void preStopHook() { /* hook for subclasses */ }
    /**
     * @see #preInitHook()
     */
    protected void postStopHook() { /* hook for subclasses */ }

    //----------------------------------------------------------------------------------------------
    // Thunking
    //----------------------------------------------------------------------------------------------

    /**
     * Advanced: Note that the receiver is the party which should handle internal requests for the
     * current thread.
     *
     * This is called automatically for the main() thread. If you choose in your code to spawn 
     * additional worker synchronous threads, each of those threads should call this method near 
     * the thread's beginning in order that access to the (thunked) hardware objects will function 
     * correctly from that thread.
     *
     * It is the act of calling setThreadThunker that makes a thread into a 'synchronous thread',
     * capable of internal calls on over to the loop() thread.
     */
    public void setThreadThunker()
        {
        if (BuildConfig.DEBUG) Assert.assertEquals(false, this.isLoopThread());
        SynchronousThreadContext.setThreadThunker(this);
        }

    /**
     * Advanced: If we are running on a synchronous thread, then return the object
     * which is managing the internal from the current thread to the loop() thread.
     * If we are not on a synchronous thread, then the behaviour is undefined.
     */
    public static IThunker getThreadThunker()
        {
        return SynchronousThreadContext.getThreadContext().getThunker();
        }

    private static SynchronousOpMode getSynchronousOpMode()        
        {
        return (SynchronousOpMode)(getThreadThunker());
        }


    /**
     * Advanced: wait until all thunks that have been dispatched from the current (synchronous)
     * thread have completed their execution over on the loop() thread.
     *
     * In general, thunked methods that don't return any information to the caller
     * (that is, the majority of setXXX() calls) only *initiate* their work on the loop()
     * thread before returning to their caller; the work may or may not have been completed
     * by the time the setXXX() call returns. Calling waitForThreadCallsToComplete()
     * allows one to wait later for these calls to do their things. waitForThreadCallsToComplete()
     * will not return until all outstanding work that has been dispatched from the current
     * thread has completed its execution over on the loop thread.
     *
     * Note that work dispatched from *other* (synchronous) threads may still be pending
     * when waitForThreadCallsToComplete() returns.
     */
    public void waitForThreadCallsToComplete() throws InterruptedException
        {
        SynchronousThreadContext.getThreadContext().waitForThreadThunkCompletions();
        }



    /**
     * Advanced: Answer as to whether the current thread is in fact the loop thread
     */
    private boolean isLoopThread()
        {
        return this.loopThread.getId() == Thread.currentThread().getId();
        }
    /**
     * Advanced: Answer as to whether this is a synchronous thread
     */
    private boolean isSynchronousThread()
        {
        return SynchronousThreadContext.isSynchronousThread();
        }

    //----------------------------------------------------------------------------------------------
    // IThunker
    //----------------------------------------------------------------------------------------------

    /**
     * Advanced: Execute the indicated action on the loop thread given that we are on a synchronous thread
     */
    public void executeOnLoopThread(IAction action)
        {
        if (BuildConfig.DEBUG) Assert.assertEquals(true, this.isSynchronousThread());
        this.actionQueueAndHistory.add(action);
        }

    /**
     * Advanced: Execute the indicated action on the loop thread if we are on a synchronous thread
     * or the loop() thread.
     *
     * If a previous call has been made with the same key, then replace that previous action;
     * otherwise, add a new action with the key.
     */
    public void executeSingletonOnLoopThread(int key, IAction action)
        {
        if (BuildConfig.DEBUG) Assert.assertEquals(true, this.isSynchronousThread());
        synchronized (this.singletonLoopActions)
            {
            this.singletonLoopActions.put(key, action);
            }
        }

    private ArrayList<IAction> snarfSingletons()
    // Atomically retrieve a copy of the singleton loop actions. The lock on that object
    // is a leaf lock, meaning that no further locks may be acquired if that lock is held.
    // By this protocol we avoid deadlock, and that is a wonderful thing.
        {
        ArrayList<IAction> result = new ArrayList<IAction>();
        synchronized (this.singletonLoopActions)
            {
            for (int i = 0; i < this.singletonLoopActions.size(); i++)
                {
                int key = this.singletonLoopActions.keyAt(i);
                result.add(this.singletonLoopActions.get(key));
                }
            this.singletonLoopActions.clear();
            }
        return result;
        }

    private void clearSingletons()
        {
        synchronized (this.singletonLoopActions)
            {
            this.singletonLoopActions.clear();
            }
        }

    /**
     * Return a new key by which actions can be scheduled using executeSingletonOnLoopThread()
     */
    public static int getNewExecuteSingletonKey()
        {
        return singletonKey.getAndIncrement();
        }


    //----------------------------------------------------------------------------------------------
    // Thunking hardware map
    //----------------------------------------------------------------------------------------------

    /**
     * Rare: Given a (non-internal) hardware map, create a new hardware map containing
     * all the same devices but in a form that their methods thunk from the main()
     * thread to the loop() thread.
     */
    public static HardwareMap createThunkedHardwareMap(HardwareMap hwmap)
        {
        HardwareMap result = new HardwareMap();

        createThunks(hwmap.dcMotorController, result.dcMotorController,
                new IThunkFactory<DcMotorController>()
                {
                @Override public DcMotorController create(DcMotorController target)
                    {
                    return ThunkedMotorController.create(target);
                    }
                }
        );

        createThunks(hwmap.servoController, result.servoController,
                new IThunkFactory<ServoController>()
                {
                @Override public ServoController create(ServoController target)
                    {
                    return ThunkedServoController.create(target);
                    }
                }
        );

        createThunks(hwmap.legacyModule, result.legacyModule,
                new IThunkFactory<LegacyModule>()
                {
                @Override public LegacyModule create(LegacyModule target)
                    {
                    return ThunkedLegacyModule.create(target);
                    }
                }
        );

        createThunks(hwmap.deviceInterfaceModule, result.deviceInterfaceModule,
                new IThunkFactory<DeviceInterfaceModule>()
                {
                @Override public DeviceInterfaceModule create(DeviceInterfaceModule target)
                    {
                    return ThunkedDeviceInterfaceModule.create(target);
                    }
                }
        );

        createThunks(hwmap.analogInput, result.analogInput,
                new IThunkFactory<AnalogInput>()
                {
                @Override public AnalogInput create(AnalogInput target)
                    {
                    return new ThreadSafeAnalogInput(
                        ThunkedAnalogInputController.create(ThreadSafeAnalogInput.getController(target)),
                        ThreadSafeAnalogInput.getChannel(target)
                        );
                    }
                }
        );

        createThunks(hwmap.analogOutput, result.analogOutput,
                new IThunkFactory<AnalogOutput>()
                {
                @Override public AnalogOutput create(AnalogOutput target)
                    {
                    return new ThreadSafeAnalogOutput(
                            ThunkedAnalogOutputController.create(ThreadSafeAnalogOutput.getController(target)),
                            ThreadSafeAnalogOutput.getChannel(target)
                    );
                    }
                }
        );

        createThunks(hwmap.pwmOutput, result.pwmOutput,
                new IThunkFactory<PWMOutput>()
                {
                @Override public PWMOutput create(PWMOutput target)
                    {
                    return new ThreadSafePWMOutput(
                            ThunkedPWMOutputController.create(ThreadSafePWMOutput.getController(target)),
                            ThreadSafePWMOutput.getChannel(target)
                    );
                    }
                }
        );

        createThunks(hwmap.i2cDevice, result.i2cDevice,
                new IThunkFactory<I2cDevice>()
                {
                @Override public I2cDevice create(I2cDevice target)
                    {
                    return new ThreadSafeI2cDevice(
                            ThunkedI2cController.create(ThreadSafeI2cDevice.getController(target)),
                            ThreadSafeI2cDevice.getChannel(target)
                    );
                    }
                }
        );

        createThunks(hwmap.digitalChannel, result.digitalChannel,
                new IThunkFactory<DigitalChannel>()
                {
                @Override public DigitalChannel create(DigitalChannel target)
                    {
                    return new ThreadSafeDigitalChannel(
                            ThunkedDigitalChannelController.create(ThreadSafeDigitalChannel.getController(target)),
                            ThreadSafeDigitalChannel.getChannel(target)
                        );
                    }
                }
        );

        createThunks(hwmap.dcMotor, result.dcMotor,
                new IThunkFactory<DcMotor>()
                {
                @Override public DcMotor create(DcMotor target)
                    {
                    return new ThreadSafeDcMotor(
                        ThunkedMotorController.create(target.getController()),
                        target.getPortNumber(),
                        target.getDirection()
                    );
                    }
                }
        );

        createThunks(hwmap.servo, result.servo,
                new IThunkFactory<com.qualcomm.robotcore.hardware.Servo>()
                {
                @Override
                public com.qualcomm.robotcore.hardware.Servo create(com.qualcomm.robotcore.hardware.Servo target)
                    {
                    return new ThreadSafeServo(
                        ThunkedServoController.create(target.getController()),
                        target.getPortNumber(),
                        target.getDirection()
                    );
                    }
                }
        );

        createThunks(hwmap.accelerationSensor, result.accelerationSensor,
                new IThunkFactory<AccelerationSensor>()
                {
                @Override public AccelerationSensor create(AccelerationSensor target)
                    {
                    return ThunkedAccelerationSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.compassSensor, result.compassSensor,
                new IThunkFactory<CompassSensor>()
                {
                @Override public CompassSensor create(CompassSensor target)
                    {
                    return ThunkedCompassSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.gyroSensor, result.gyroSensor,
                new IThunkFactory<GyroSensor>()
                {
                @Override public GyroSensor create(GyroSensor target)
                    {
                    return ThunkedGyroSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.irSeekerSensor, result.irSeekerSensor,
                new IThunkFactory<IrSeekerSensor>()
                {
                @Override public IrSeekerSensor create(IrSeekerSensor target)
                    {
                    return ThunkedIrSeekerSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.lightSensor, result.lightSensor,
                new IThunkFactory<LightSensor>()
                {
                @Override public LightSensor create(LightSensor target)
                    {
                    return ThunkedLightSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.opticalDistanceSensor, result.opticalDistanceSensor,
                new IThunkFactory<OpticalDistanceSensor>()
                {
                @Override public OpticalDistanceSensor create(OpticalDistanceSensor target)
                    {
                    return ThunkedOpticalDistanceSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.touchSensor, result.touchSensor,
                new IThunkFactory<TouchSensor>()
                {
                @Override public TouchSensor create(TouchSensor target)
                    {
                    return ThunkedTouchSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.ultrasonicSensor, result.ultrasonicSensor,
                new IThunkFactory<UltrasonicSensor>()
                {
                @Override public UltrasonicSensor create(UltrasonicSensor target)
                    {
                    return ThunkedUltrasonicSensor.create(target);
                    }
                }
        );

        createThunks(hwmap.voltageSensor, result.voltageSensor,
                new IThunkFactory<VoltageSensor>()
                {
                @Override public VoltageSensor create(VoltageSensor target)
                    {
                    return ThunkedVoltageSensor.create(target);
                    }
                }
        );

        result.appContext = hwmap.appContext;

        return result;
        }

    private interface IThunkFactory<T>
        {
        T create(T t);
        }

    private static <T> void createThunks(HardwareMap.DeviceMapping<T> from, HardwareMap.DeviceMapping<T> to, IThunkFactory<T> thunkFactory)
        {
        for (Map.Entry<String,T> pair : from.entrySet())
            {
            T thunked = thunkFactory.create(pair.getValue());
            to.put(pair.getKey(), thunked);
            }
        }
    }