package com.spake.brony.doubaoassistant.speech.kws

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

internal object KwsModel {
    const val SAMPLE_RATE = 16_000

    fun create(assets: AssetManager): KeywordSpotter = KeywordSpotter(
        assetManager = assets,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.int8.onnx",
                    decoder = "kws/decoder.onnx",
                    joiner = "kws/joiner.int8.onnx",
                ),
                tokens = "kws/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            // createStream 会合并默认词表；保持空表，所有口令由当前昵称生成。
            keywordsFile = "kws/base_keywords.txt",
            keywordsScore = 1.5f,
            // shortcut: 初始门限尚未针对车内噪声标定，取得实车录音后再调整。
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 2,
        ),
    )
}
