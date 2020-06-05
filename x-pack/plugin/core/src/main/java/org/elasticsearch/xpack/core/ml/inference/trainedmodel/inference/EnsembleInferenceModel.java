/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.ml.inference.trainedmodel.inference;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.core.ml.inference.results.ClassificationInferenceResults;
import org.elasticsearch.xpack.core.ml.inference.results.InferenceResults;
import org.elasticsearch.xpack.core.ml.inference.results.RawInferenceResults;
import org.elasticsearch.xpack.core.ml.inference.results.RegressionInferenceResults;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ClassificationConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceHelpers;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.NullInferenceConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TargetType;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.LenientlyParsedOutputAggregator;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.OutputAggregator;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceHelpers.classificationLabel;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceHelpers.decodeFeatureImportances;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceHelpers.sumDoubleArrays;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceHelpers.transformFeatureImportance;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.Ensemble.AGGREGATE_OUTPUT;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.Ensemble.CLASSIFICATION_LABELS;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.Ensemble.CLASSIFICATION_WEIGHTS;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.Ensemble.FEATURE_NAMES;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.Ensemble.TARGET_TYPE;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.ensemble.Ensemble.TRAINED_MODELS;

public class EnsembleInferenceModel implements InferenceModel {

    private static final long SHALLOW_SIZE = RamUsageEstimator.shallowSizeOfInstance(EnsembleInferenceModel.class);

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<EnsembleInferenceModel, Void> PARSER = new ConstructingObjectParser<>(
        "ensemble_inference_model",
        true,
        a -> new EnsembleInferenceModel((List<String>)a[0],
            (List<InferenceModel>)a[1],
            (OutputAggregator)a[2],
            TargetType.fromString((String)a[3]),
            (List<String>)a[4],
            (List<Double>)a[5]));
    static {
        PARSER.declareStringArray(constructorArg(), FEATURE_NAMES);
        PARSER.declareNamedObjects(constructorArg(),
            (p, c, n) -> p.namedObject(InferenceModel.class, n, null),
            (ensembleBuilder) -> {},
            TRAINED_MODELS);
        PARSER.declareNamedObject(constructorArg(),
            (p, c, n) -> p.namedObject(LenientlyParsedOutputAggregator.class, n, null),
            AGGREGATE_OUTPUT);
        PARSER.declareString(constructorArg(), TARGET_TYPE);
        PARSER.declareStringArray(optionalConstructorArg(), CLASSIFICATION_LABELS);
        PARSER.declareDoubleArray(optionalConstructorArg(), CLASSIFICATION_WEIGHTS);
    }

    public static EnsembleInferenceModel fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    private String[] featureNames;
    private final List<InferenceModel> models;
    private final OutputAggregator outputAggregator;
    private final TargetType targetType;
    private final List<String> classificationLabels;
    private final double[] classificationWeights;

    EnsembleInferenceModel(List<String> featureNames,
                           List<InferenceModel> models,
                           OutputAggregator outputAggregator,
                           TargetType targetType,
                           List<String> classificationLabels,
                           List<Double> classificationWeights) {
        this.featureNames = ExceptionsHelper.requireNonNull(featureNames, FEATURE_NAMES).toArray(new String[0]);
        this.models = ExceptionsHelper.requireNonNull(models, TRAINED_MODELS);
        this.outputAggregator = ExceptionsHelper.requireNonNull(outputAggregator, AGGREGATE_OUTPUT);
        this.targetType = ExceptionsHelper.requireNonNull(targetType, TARGET_TYPE);
        this.classificationLabels = classificationLabels == null ? null : classificationLabels;
        this.classificationWeights = classificationWeights == null ?
            null :
            classificationWeights.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Override
    public String[] getFeatureNames() {
        return featureNames;
    }

    @Override
    public TargetType targetType() {
        return targetType;
    }

    private double[] getFeatures(Map<String, Object> fields) {
        double[] features = new double[featureNames.length];
        int i = 0;
        for (String featureName : featureNames) {
            Double val = InferenceHelpers.toDouble(fields.get(featureName));
            features[i++] = val == null ? Double.NaN : val;
        }
        return features;
    }

    @Override
    public InferenceResults infer(Map<String, Object> fields, InferenceConfig config, Map<String, String> featureDecoderMap) {
        return innerInfer(getFeatures(fields), config, featureDecoderMap);
    }

    @Override
    public InferenceResults infer(double[] features, InferenceConfig config) {
        return innerInfer(features, config, Collections.emptyMap());
    }

    private InferenceResults innerInfer(double[] features, InferenceConfig config, Map<String, String> featureDecoderMap) {
        if (config.isTargetTypeSupported(targetType) == false) {
            throw ExceptionsHelper.badRequestException(
                "Cannot infer using configuration for [{}] when model target_type is [{}]", config.getName(), targetType.toString());
        }
        double[][] inferenceResults = new double[this.models.size()][];
        double[][] featureInfluence = new double[features.length][];
        int i = 0;
        NullInferenceConfig subModelInferenceConfig = new NullInferenceConfig(config.requestingImportance());
        for (InferenceModel model : models) {
            InferenceResults result = model.infer(features, subModelInferenceConfig);
            assert result instanceof RawInferenceResults;
            RawInferenceResults inferenceResult = (RawInferenceResults) result;
            inferenceResults[i++] = inferenceResult.getValue();
            if (config.requestingImportance()) {
                double[][] modelFeatureImportance = inferenceResult.getFeatureImportance();
                assert modelFeatureImportance.length == featureInfluence.length;
                for (int j = 0; j < modelFeatureImportance.length; j++) {
                    if (featureInfluence[j] == null) {
                        featureInfluence[j] = new double[modelFeatureImportance[j].length];
                    }
                    featureInfluence[j] = sumDoubleArrays(featureInfluence[j], modelFeatureImportance[j]);
                }
            }
        }
        double[] processed = outputAggregator.processValues(inferenceResults);
        return buildResults(processed, featureInfluence, featureDecoderMap, config);
    }

    //For testing
    double[][] featureImportance(double[] features) {
        double[][] featureInfluence = new double[features.length][];
        NullInferenceConfig subModelInferenceConfig = new NullInferenceConfig(true);
        for (InferenceModel model : models) {
            InferenceResults result = model.infer(features, subModelInferenceConfig);
            assert result instanceof RawInferenceResults;
            RawInferenceResults inferenceResult = (RawInferenceResults) result;
            double[][] modelFeatureImportance = inferenceResult.getFeatureImportance();
            assert modelFeatureImportance.length == featureInfluence.length;
            for (int j = 0; j < modelFeatureImportance.length; j++) {
                if (featureInfluence[j] == null) {
                    featureInfluence[j] = new double[modelFeatureImportance[j].length];
                }
                featureInfluence[j] = sumDoubleArrays(featureInfluence[j], modelFeatureImportance[j]);
            }
        }
        return featureInfluence;
    }

    private InferenceResults buildResults(double[] processedInferences,
                                          double[][] featureImportance,
                                          Map<String, String> featureDecoderMap,
                                          InferenceConfig config) {
        // Indicates that the config is useless and the caller just wants the raw value
        if (config instanceof NullInferenceConfig) {
            return new RawInferenceResults(
                new double[] {outputAggregator.aggregate(processedInferences)},
                featureImportance);
        }
        Map<String, double[]> decodedFeatureImportance = config.requestingImportance() ?
            decodeFeatureImportances(featureDecoderMap,
                IntStream.range(0, featureImportance.length)
                    .boxed()
                    .collect(Collectors.toMap(i -> featureNames[i], i -> featureImportance[i]))) :
            Collections.emptyMap();
        switch(targetType) {
            case REGRESSION:
                return new RegressionInferenceResults(outputAggregator.aggregate(processedInferences),
                    config,
                    transformFeatureImportance(decodedFeatureImportance, null));
            case CLASSIFICATION:
                ClassificationConfig classificationConfig = (ClassificationConfig) config;
                assert classificationWeights == null || processedInferences.length == classificationWeights.length;
                // Adjust the probabilities according to the thresholds
                Tuple<Integer, List<ClassificationInferenceResults.TopClassEntry>> topClasses = InferenceHelpers.topClasses(
                    processedInferences,
                    classificationLabels,
                    classificationWeights,
                    classificationConfig.getNumTopClasses(),
                    classificationConfig.getPredictionFieldType());
                return new ClassificationInferenceResults((double)topClasses.v1(),
                    classificationLabel(topClasses.v1(), classificationLabels),
                    topClasses.v2(),
                    transformFeatureImportance(decodedFeatureImportance, classificationLabels),
                    config);
            default:
                throw new UnsupportedOperationException("unsupported target_type [" + targetType + "] for inference on ensemble model");
        }
    }

    @Override
    public boolean supportsFeatureImportance() {
        return models.stream().allMatch(InferenceModel::supportsFeatureImportance);
    }

    @Override
    public String getName() {
        return "ensemble";
    }

    @Override
    public void rewriteFeatureIndices(Map<String, Integer> newFeatureIndexMapping) {
        if (newFeatureIndexMapping == null || newFeatureIndexMapping.isEmpty()) {
            Set<String> referencedFeatures = subModelFeatures();
            int newFeatureIndex = 0;
            newFeatureIndexMapping = new HashMap<>();
            this.featureNames = new String[referencedFeatures.size()];
            for (String featureName : referencedFeatures) {
                newFeatureIndexMapping.put(featureName, newFeatureIndex);
                this.featureNames[newFeatureIndex++] = featureName;
            }
        } else {
            this.featureNames = new String[0];
        }
        for (InferenceModel model : models) {
            model.rewriteFeatureIndices(newFeatureIndexMapping);
        }
    }

    private Set<String> subModelFeatures() {
        Set<String> referencedFeatures = new LinkedHashSet<>();
        for (InferenceModel model : models) {
            if (model instanceof EnsembleInferenceModel) {
                referencedFeatures.addAll(((EnsembleInferenceModel) model).subModelFeatures());
            } else {
                for (String featureName : model.getFeatureNames()) {
                    referencedFeatures.add(featureName);
                }
            }
        }
        return referencedFeatures;
    }

    @Override
    public long ramBytesUsed() {
        long size = SHALLOW_SIZE;
        size += RamUsageEstimator.sizeOf(featureNames);
        size += RamUsageEstimator.sizeOfCollection(classificationLabels);
        size += RamUsageEstimator.sizeOfCollection(models);
        if (classificationWeights != null) {
            size += RamUsageEstimator.sizeOf(classificationWeights);
        }
        size += outputAggregator.ramBytesUsed();
        return size;
    }
}