package fr.cea.lbi;

import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.parallelism.ParallelWrapper;
import org.nd4j.jita.conf.CudaEnvironment;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class App {
    private static final transient Logger LOGGER = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        // PLEASE NOTE: For CUDA FP16 precision support is available
        Nd4j.setDataType(DataBuffer.Type.HALF);

        // temp workaround for backend initialization

        CudaEnvironment.getInstance().getConfiguration()
                       // key option enabled
                       .allowMultiGPU(true)
                       // we're allowing larger memory caches
                       .setMaximumDeviceCache(2L * 1024L * 1024L * 1024L)
                       // cross-device access is used for faster model averaging over pcie
                       .allowCrossDeviceAccess(true);

        int nChannels = 1;
        int outputNum = 10;

        // for GPU you usually want to have higher batchSize
        int batchSize = 128;
        int nEpochs = 10;
        int seed = 123;

        LOGGER.info("Load data....");
        DataSetIterator mnistTrain = new MnistDataSetIterator(batchSize, true, 12345);
        DataSetIterator mnistTest = new MnistDataSetIterator(batchSize, false, 12345);

        LOGGER.info("Build model....");
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                                               .seed(seed)
                                               .l2(0.0005)
                                               .weightInit(WeightInit.XAVIER)
                                               .updater(new Nesterovs.Builder().learningRate(.01).build())
                                               .biasInit(0.0)
//                                               .learningRate(0.02)
                                               .list()
                                               .layer(0, new ConvolutionLayer.Builder(5, 5)
                                                                 //nIn and nOut specify depth. nIn here is the nChannels and nOut is the number of filters to be applied
                                                                 .nIn(nChannels)
                                                                 .stride(1, 1)
                                                                 .nOut(20)
                                                                 .activation(Activation.IDENTITY)
                                                                 .build())
                                               .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                                                                 .kernelSize(2, 2)
                                                                 .stride(2, 2)
                                                                 .build())
                                               .layer(2, new ConvolutionLayer.Builder(5, 5)
                                                                 //Note that nIn need not be specified in later layers
                                                                 .stride(1, 1)
                                                                 .nOut(50)
                                                                 .activation(Activation.IDENTITY)
                                                                 .build())
                                               .layer(3, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                                                                 .kernelSize(2, 2)
                                                                 .stride(2, 2)
                                                                 .build())
                                               .layer(4, new DenseLayer.Builder().activation(Activation.RELU)
                                                                                 .nOut(500).build())
                                               .layer(5, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                                                 .nOut(outputNum)
                                                                 .activation(Activation.SOFTMAX)
                                                                 .build())
                                               .setInputType(InputType.convolutionalFlat(28, 28, 1)) //See note below
                                               .backprop(true).pretrain(false).build();
        MultiLayerNetwork model = new MultiLayerNetwork(conf);
        model.init();

        // ParallelWrapper will take care of load balancing between GPUs.
        ParallelWrapper wrapper = new ParallelWrapper.Builder<>(model)
                                          // DataSets prefetching options. Set this value with respect to number of actual devices
                                          .prefetchBuffer(24)
                                          // set number of workers equal to number of available devices. x1-x2 are good values to start with
                                          .workers(2)
                                          // rare averaging improves performance, but might reduce model accuracy
                                          .averagingFrequency(3)
                                          // if set to TRUE, on every averaging model score will be reported
                                          .reportScoreAfterAveraging(true)
                                          .build();

        LOGGER.info("Train model....");
        model.setListeners(new ScoreIterationListener(100));
        long timeX = System.currentTimeMillis();

        // optionally you might want to use MultipleEpochsIterator instead of manually iterating/resetting over your iterator
        //MultipleEpochsIterator mnistMultiEpochIterator = new MultipleEpochsIterator(nEpochs, mnistTrain);

        for (int i = 0; i < nEpochs; i++) {
            long time1 = System.currentTimeMillis();

            // Please note: we're feeding ParallelWrapper with iterator, not model directly
//            wrapper.fit(mnistMultiEpochIterator);
            wrapper.fit(mnistTrain);
            long time2 = System.currentTimeMillis();
            LOGGER.info("*** Completed epoch {}, time: {} ***", i, (time2 - time1));
        }
        long timeY = System.currentTimeMillis();

        LOGGER.info("*** Training complete, time: {} ***", (timeY - timeX));

        LOGGER.info("Evaluate model....");
        Evaluation eval = new Evaluation(outputNum);
        while (mnistTest.hasNext()) {
            DataSet ds = mnistTest.next();
            INDArray output = model.output(ds.getFeatureMatrix(), false);
            eval.eval(ds.getLabels(), output);
        }
        LOGGER.info(eval.stats());
        mnistTest.reset();

        LOGGER.info("****************Example finished********************");
    }
}
