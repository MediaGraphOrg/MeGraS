from PIL import Image
from transformers import CLIPProcessor, CLIPModel
import torch
import torch.nn.functional as F
from typing import List, Union
import io # Import io for BytesIO

class CLIPEmbedder:
    """
    A class for generating text and image embeddings using the OpenAI CLIP model.
    """

    def __init__(self, model_name: str = "openai/clip-vit-base-patch32", device: str = "cpu"):
        """
        Initializes the CLIPEmbedder with a specified model and device.

        Args:
            model_name (str, optional): The name of the CLIP model to use.
                Defaults to "openai/clip-vit-base-patch32".
            device (str, optional): The device to run the model on ("cpu" or "cuda").
                Defaults to "cpu".
        """
        self.model_name = model_name
        self.device = device
        self.model = CLIPModel.from_pretrained(self.model_name).to(self.device)
        self.processor = CLIPProcessor.from_pretrained(self.model_name)

    def get_text_embeddings(self, text: str) -> torch.Tensor:
        """
        Computes text embeddings for a single text input string.

        Args:
            text (str): The input text string.

        Returns:
            torch.Tensor: A tensor containing the normalized text embeddings.
        """
        inputs = self.processor(text=[text], return_tensors="pt").to(self.device)
        with torch.no_grad():
            outputs = self.model(**inputs)
            text_embeddings = outputs.text_embeds
        return F.normalize(text_embeddings, dim=-1)

    def get_image_embeddings(self, image_bytes: bytes) -> torch.Tensor: # Still takes image_bytes
        """
        Computes image embeddings for given image bytes.

        Args:
            image_bytes (bytes): The raw bytes of the image file.

        Returns:
            torch.Tensor: A tensor containing the normalized image embeddings.
        """
        try:
            # Use io.BytesIO to treat the bytes as a file
            image = Image.open(io.BytesIO(image_bytes))
        except Exception as e:
            raise Exception(f"Failed to load image from bytes. Error: {e}")

        if image.mode != 'RGB':
            image = image.convert('RGB')

        inputs = self.processor(images=image, return_tensors="pt").to(self.device)
        with torch.no_grad():
            outputs = self.model(**inputs)
            image_embeddings = outputs.image_embeds
        return F.normalize(image_embeddings, dim=-1)