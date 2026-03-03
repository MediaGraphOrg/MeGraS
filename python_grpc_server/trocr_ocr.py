from PIL import Image
from transformers import TrOCRProcessor, VisionEncoderDecoderModel
import io
import torch

class TrOCROCR:
    """
    A class for performing Optical Character Recognition (OCR) using a TrOCR model,
    optimized for printed and scene text common in lifelog datasets.
    """

    def __init__(self, model_name: str = "microsoft/trocr-base-printed", device: str = "cpu"):
        """
        Initializes the TrOCROCR with a specified model and device.

        Args:
            model_name (str, optional): The name of the TrOCR model to use.
                Defaults to "microsoft/trocr-base-printed" which is suitable for signs and printed text.
                Consider "microsoft/trocr-large-printed" for higher accuracy with more computational cost.
            device (str, optional): The device to run the model on ("cpu" or "cuda").
                Defaults to "cpu".
        """
        self.device = device
        self.processor = TrOCRProcessor.from_pretrained(model_name)
        self.model = VisionEncoderDecoderModel.from_pretrained(model_name)


    def recognize_text(self, image_bytes: bytes) -> str:
        """
        Performs OCR on the given image bytes and returns the recognized text.

        Args:
            image_bytes (bytes): The raw bytes of the image file.

        Returns:
            str: The recognized text from the image.
        """
        try:
            image = Image.open(io.BytesIO(image_bytes))
        except Exception as e:
            raise Exception(f"Failed to load image from bytes. Error: {e}")

        # TrOCR models typically expect RGB images
        if image.mode != 'RGB':
            image = image.convert('RGB')

        # Prepare image for the model. The processor handles resizing, normalization, etc.
        pixel_values = self.processor(images=image, return_tensors="pt").pixel_values
        pixel_values = pixel_values.to(self.device)

        with torch.no_grad():
            generated_ids = self.model.generate(pixel_values)

        # Decode the generated IDs to text, skipping special tokens like [CLS], [SEP]
        recognized_text = self.processor.batch_decode(generated_ids, skip_special_tokens=True)[0]
        return recognized_text