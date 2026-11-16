from types import ModuleType, SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch

from server.cosy_runtime import _load_weights, enable_decoder_only, load_cosyvoice


class DecoderOnlyTests(unittest.TestCase):
    def test_decoder_states_and_cache_without_unused_vocabulary_projection(self):
        import numpy as np

        encoder = type("Qwen2Encoder", (), {})()
        encoder.model = Mock()
        encoder.model.config.model_type = "qwen2"
        outputs = SimpleNamespace(last_hidden_state=object(), past_key_values=object())
        encoder.model.model.return_value = outputs
        model = SimpleNamespace(model=SimpleNamespace(llm=SimpleNamespace(llm=encoder)))
        enable_decoder_only(model)
        xs, cache = object(), object()
        masks = np.arange(12).reshape(1, 3, 4)
        self.assertEqual((outputs.last_hidden_state, outputs.past_key_values),
                         encoder.forward_one_step(xs, masks, cache))
        encoder.model.assert_not_called()
        args = encoder.model.model.call_args.kwargs
        self.assertIs(xs, args["inputs_embeds"])
        np.testing.assert_array_equal(masks[:, -1, :], args["attention_mask"])
        self.assertIs(cache, args["past_key_values"])
        self.assertTrue(args["use_cache"])
        self.assertFalse(args["output_hidden_states"])

    def test_unknown_encoder_is_rejected(self):
        model = SimpleNamespace(model=SimpleNamespace(llm=SimpleNamespace(llm=Mock())))
        with self.assertRaisesRegex(RuntimeError, "Unsupported CosyVoice decoder"):
            enable_decoder_only(model)


class LowMemoryRuntimeTests(unittest.TestCase):
    def test_cpu_mapped_weights_and_vocoder_precision(self):
        torch = Mock()
        torch.load.side_effect = [{"llm": 1}, {"flow": 2}, {"generator.weight": 3}]
        model = SimpleNamespace(fp16=True, device="cuda", llm=Mock(), flow=Mock(), hift=Mock())
        _load_weights(model, torch, "llm.pt", "flow.pt", "hift.pt")
        for call in torch.load.call_args_list:
            self.assertEqual({"map_location": "cpu", "weights_only": True, "mmap": True}, call.kwargs)
        model.llm.half.assert_called_once()
        model.flow.half.assert_called_once()
        model.hift.half.assert_not_called()
        model.hift.load_state_dict.assert_called_once_with({"weight": 3}, strict=True, assign=True)
        for module in (model.llm, model.flow, model.hift):
            module.to.assert_called_once_with("cuda")
            module.to.return_value.eval.assert_called_once()

    def test_constructor_failure_restores_inherited_loader(self):
        class Parent:
            def load(self, *_): pass
        class Model(Parent): pass
        mod = ModuleType("cosyvoice.cli.model")
        mod.CosyVoice3Model = Model
        ort = ModuleType("onnxruntime")
        ort.InferenceSession = Mock()
        original_session = ort.InferenceSession
        with patch.dict(sys.modules, {"cosyvoice.cli.model": mod, "onnxruntime": ort}):
            with self.assertRaisesRegex(RuntimeError, "loading failed"):
                load_cosyvoice(Mock(side_effect=RuntimeError("loading failed")), Mock(),
                               model_dir="model", low_memory=True)
        self.assertNotIn("load", Model.__dict__)
        self.assertIs(Model.load, Parent.load)
        self.assertIs(ort.InferenceSession, original_session)

    def test_only_reference_tokenizer_moves_to_cpu(self):
        class Model:
            load = Mock()
        mod = ModuleType("cosyvoice.cli.model")
        mod.CosyVoice3Model = Model
        ort = ModuleType("onnxruntime")
        original_session = ort.InferenceSession = Mock()
        options = SimpleNamespace(enable_cpu_mem_arena=True, enable_mem_pattern=True)
        def construct(**_):
            ref = ort.InferenceSession("/model/speech_tokenizer_v3.onnx", sess_options=options,
                                 providers=["CUDAExecutionProvider"])
            original_session.assert_not_called()
            ref.get_inputs()
            ref.run(None, {})
            self.assertIsNone(ref.session)
            ort.InferenceSession("/model/campplus.onnx", providers=["CPUExecutionProvider"])
        with patch.dict(sys.modules, {"cosyvoice.cli.model": mod, "onnxruntime": ort}):
            load_cosyvoice(construct, Mock(), model_dir="model", low_memory=True)
        self.assertEqual(["CPUExecutionProvider"], original_session.call_args_list[0].kwargs["providers"])
        self.assertEqual(["CPUExecutionProvider"], original_session.call_args_list[1].kwargs["providers"])
        self.assertFalse(options.enable_cpu_mem_arena)
        self.assertFalse(options.enable_mem_pattern)

    def test_default_path_preserves_upstream_construction(self):
        factory = Mock()
        self.assertIs(load_cosyvoice(factory, Mock(), model_dir="model"), factory.return_value)
        factory.assert_called_once_with(model_dir="model", fp16=True)
