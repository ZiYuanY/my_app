package FSK;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
//来源网络
public class FSKDecoder {
    public interface FSKDecoderCallback {
        public void decoded(byte[] newData);
    }
    protected Runnable mProcessor = new Runnable() {

        @Override
        public void run() {

            while (mRunning) {

                synchronized (mSignal) {

                    switch (mDecoderStatus) {
                        case IDLE:
                            stop();

                            break;
                        case SEARCHING_SIGNAL:case SEARCHING_START_BIT:
                            processIterationSearch();

                            break;

                        case DECODING:
                            processIterationDecode();

                            break;

                    }
                }
            }
        }
    };
    protected enum STATE {
        HIGH, LOW, SILENCE, UNKNOWN
    }

    protected enum DecoderStatus {
        IDLE, SEARCHING_SIGNAL, SEARCHING_START_BIT, DECODING
    }
    protected FSKConfig mConfig;
    protected FSKDecoderCallback mCallback;
    protected Thread mThread;
    protected boolean mRunning = false;
    protected DecoderStatus mDecoderStatus = DecoderStatus.IDLE;
    protected DecoderStatus mDecoderStatusPaused = DecoderStatus.IDLE;
    protected ShortBuffer mSignal;
    protected ShortBuffer mFrame;
    protected StringBuffer mBitBuffer;
    protected int mCurrentBit = 0;
    protected int mSignalEnd = 0;
    protected int mSignalPointer = 0;
    protected int mSignalBufferSize = 0;
    protected ByteBuffer mData;
    protected int mDataLength = 0;
    public FSKDecoder(FSKConfig config, FSKDecoderCallback callback) {
        mConfig = config;
        mCallback = callback;
        mSignalBufferSize = mConfig.sampleRate;
        allocateBufferSignal();
        allocateBufferFrame();
        allocateBufferData();
    }
    @Override
    protected void finalize() throws Throwable {
        stop();
        super.finalize();
    }
    protected void notifyCallback(byte[] data) {
        if (mCallback != null) {
            mCallback.decoded(data);
        }
    }
    protected void start() {
        if (!mRunning) {
            if (!mDecoderStatusPaused.equals(DecoderStatus.IDLE)) {
                setStatus(mDecoderStatusPaused); //resume task
            }
            else {
                setStatus(DecoderStatus.SEARCHING_SIGNAL); //start new process
            }
            mRunning = true;
            mThread = new Thread(mProcessor);
            mThread.setPriority(Thread.MIN_PRIORITY);
            mThread.start();
        }
    }
    public void stop() {
        if (mRunning) {
            if (mThread != null && mThread.isAlive()) {
                mRunning = false;
                mThread.interrupt();
            }
        }
    }
    protected void allocateBufferSignal() {
        mSignal = ShortBuffer.allocate(mSignalBufferSize);
    }
    protected void allocateBufferFrame() {
        mFrame = ShortBuffer.allocate(mConfig.samplesPerBit); // one frame contains one bit
    }
    protected void allocateBufferData() {
        mData = ByteBuffer.allocate(FSKConfig.DECODER_DATA_BUFFER_SIZE); // maximum bytes
    }

    protected void nextStatus() {
        switch (mDecoderStatus) {
            case IDLE:
                setStatus(DecoderStatus.SEARCHING_SIGNAL);
                break;
            case SEARCHING_SIGNAL:
                setStatus(DecoderStatus.SEARCHING_START_BIT);
                break;
            case SEARCHING_START_BIT:
                setStatus(DecoderStatus.DECODING);
                break;
            case DECODING:
                setStatus(DecoderStatus.IDLE);
                break;
        }
    }
    protected void setStatus(DecoderStatus status) {
        setStatus(status, DecoderStatus.IDLE);
    }
    protected void setStatus(DecoderStatus status, DecoderStatus paused) {
        mDecoderStatus = status;
        mDecoderStatusPaused = paused;
    }
    protected short[] byteArrayToShortArray(byte[] data) {
        int size = data.length;
        short[] result = new short[size];
        for (int i = 0; i < size; i++) {
            result[i] = (short) data[i];
        }
        return result;
    }
    protected short[] convertToMono(short[] data) {
        short[] monoData = new short[data.length/2];
        for (int i = 0; i < data.length-1; i+=2) {
            int mixed = ((int)(data[i] + data[i+1]))/2;

            monoData[i/2] = (short) mixed;
        }
        return monoData;
    }
    protected void trimSignal() {
        if (mSignalPointer <= mSignalEnd) {
            short[] currentData = mSignal.array();
            short[] remainingData = new short[mSignalEnd - mSignalPointer];
            for (int i = 0; i < remainingData.length; i++) {
                remainingData[i] = currentData[mSignalPointer+i];
            }
            allocateBufferSignal();
            mSignal.put(remainingData);
            mSignal.rewind();
            mSignalPointer = 0;
            mSignalEnd = remainingData.length;
        }
        else {
            clearSignal();
        }
    }
    public int appendSignal(byte[] data) {
        return appendSignal(byteArrayToShortArray(data));
    }
    public int appendSignal(short[] data) {
        synchronized (mSignal) {
            short[] monoData;

            if (mConfig.channels == FSKConfig.CHANNELS_STEREO) {
                monoData = convertToMono(data);
            }
            else {
                monoData = data;
            }
            if (mSignalEnd + monoData.length > mSignal.capacity()) {
                if ((mSignalEnd + monoData.length)-mSignalPointer <= mSignal.capacity()) {
                    trimSignal();
                }
                else {
                    return (mSignal.capacity() - (mSignalEnd + monoData.length)); // refuse data and tell the amount of overflow
                }
            }
            mSignal.position(mSignalEnd);
            mSignal.put(monoData);
            mSignalEnd += monoData.length;
            start(); //if idle
            return (mSignal.capacity() - mSignalEnd); //return the remaining amount of space in the buffer
        }
    }
    public int setSignal(byte[] data) {
        allocateBufferData(); //reset data buffer
        clearSignal();
        return appendSignal(byteArrayToShortArray(data));
    }
    public int setSignal(short[] data) {
        allocateBufferData(); //reset data buffer
        clearSignal();
        return appendSignal(data);
    }
    public int clearSignal() {
        synchronized (mSignal) {
            allocateBufferSignal();
            mSignalEnd = 0;
            mSignalPointer = 0;
            return mSignal.capacity();
        }
    }
    protected int calcFrequencyZerocrossing(short[] data) {
        int numSamples = data.length;
        int numCrossing = 0;
        for (int i = 0; i < numSamples-1; i++) {
            if ((data[i] > 0 && data[i+1] <= 0)
                    || (data[i] < 0 && data[i+1] >= 0)) {
                numCrossing++;
            }
        }
        double numSecondsRecorded = (double) numSamples
                / (double) mConfig.sampleRate;
        double numCycles = numCrossing / 2;
        double frequency = numCycles / numSecondsRecorded;
        return (int) Math.round(frequency);
    }
    protected double rootMeanSquared(short[] data) {
        double ms = 0;
        for (int i = 0; i < data.length; i++) {
            ms += data[i] * data[i];
        }
        ms /= data.length;
        return Math.sqrt(ms);
    }
    protected STATE determineState(int frequency, double rms) {
        STATE state = STATE.UNKNOWN;
        if (rms <= mConfig.rmsSilenceThreshold) {
            state = STATE.SILENCE;
        }
        else if (frequency <= mConfig.modemFreqLowThresholdHigh) {
            state = STATE.LOW;
        }
        else if (frequency <= mConfig.modemFreqHighThresholdHigh) {
            state = STATE.HIGH;
        }
        return state;
    }
    protected short[] getFrameData(int position) {
        mSignal.position(position);
        allocateBufferFrame();
        for (int j = 0; j < mConfig.samplesPerBit; j++) {
            mFrame.put(j, mSignal.get());
        }
        return mFrame.array();
    }
    protected void flushData() {
        if (mDataLength > 0) {
            byte[] data = new byte[mDataLength];
            for (int i = 0; i < mDataLength; i++) {
                data[i] = mData.get(i);
            }
            allocateBufferData();
            mDataLength = 0;
            notifyCallback(data);
        }
    }
    protected void processIterationSearch() {
        if (mSignalPointer <= mSignalEnd-mConfig.samplesPerBit) {
            short[] frameData = getFrameData(mSignalPointer);
            int freq = calcFrequencyZerocrossing(frameData);
            STATE state = determineState(freq, rootMeanSquared(frameData));
            if (state.equals(STATE.HIGH) && mDecoderStatus.equals(DecoderStatus.SEARCHING_SIGNAL)) {
                nextStatus();
            }
            if (mDecoderStatus.equals(DecoderStatus.SEARCHING_START_BIT) && freq == mConfig.modemFreqLow && state.equals(STATE.LOW)) {
                mSignalPointer += (mConfig.samplesPerBit/2);
                nextStatus();
                return;
            }
            mSignalPointer++;
        }
        else {
            trimSignal();
            flushData();
            setStatus(DecoderStatus.IDLE);
        }
    }
    protected void processIterationDecode() {
        if (mSignalPointer <= mSignalEnd-mConfig.samplesPerBit) {
            short[] frameData = getFrameData(mSignalPointer);
            double rms = rootMeanSquared(frameData);
            int freq = calcFrequencyZerocrossing(frameData);
            STATE state = determineState(freq, rms);
            if (mCurrentBit == 0 && state.equals(STATE.LOW)) {
                mBitBuffer = new StringBuffer();
                mCurrentBit++;
            }
            else if (mCurrentBit == 0 && state.equals(STATE.HIGH)) {
                setStatus(DecoderStatus.SEARCHING_START_BIT);
            }
            else if (mCurrentBit == 9 && state.equals(STATE.HIGH)) {
                try {
                    mData.put((byte) Integer.parseInt(mBitBuffer.toString(), 2));
                    mDataLength++;
                    if (mDataLength == mData.capacity()) {
                        flushData();
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                mCurrentBit = 0;
            }
            else if (mCurrentBit > 0 && mCurrentBit < 9 && (state.equals(STATE.HIGH) || state.equals(STATE.LOW))) {
                mBitBuffer.insert(0, (state.equals(STATE.HIGH) ? 1 : 0));
                mCurrentBit++;
            }
            else {
                mBitBuffer = new StringBuffer();
                mCurrentBit = 0;
                setStatus(DecoderStatus.SEARCHING_START_BIT);
            }
            mSignalPointer += mConfig.samplesPerBit;
        }
        else {
            trimSignal();
            flushData();
            setStatus(DecoderStatus.IDLE, DecoderStatus.DECODING);
        }
    }

}
