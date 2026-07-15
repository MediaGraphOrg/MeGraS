from PIL import Image
from transformers import AutoProcessor, AutoModel
import torch
import torch.nn.functional as F
import io

class SigLIPEmbedder:
    """
    A class for generating text and image embeddings using the Google SigLIP model.
    """

    def __init__(self, model_name: str = "google/siglip-so400m-patch14-384", device: str = "cpu"):
        self.model_name = model_name
        self.device = device
        
        # Using eager attention to maintain maximum compatibility with your transformers version
        self.model = AutoModel.from_pretrained(self.model_name, attn_implementation="eager").to(self.device)
        self.processor = AutoProcessor.from_pretrained(self.model_name)

    def get_text_embeddings(self, text: str) -> torch.Tensor:
        print(f"SigLIP Text: {text}")
        inputs = self.processor(text=[text], padding="max_length", return_tensors="pt").to(self.device)
        
        with torch.no_grad():
            text_embeddings = self.model.get_text_features(**inputs)
            
            # Transformers v5 compatibility fix: extract the raw tensor if an object is returned
            if not isinstance(text_embeddings, torch.Tensor):
                text_embeddings = getattr(text_embeddings, 'pooler_output', text_embeddings[0])
            
        return F.normalize(text_embeddings, dim=-1)

    def get_image_embeddings(self, image_bytes: bytes) -> torch.Tensor:
        try:
            image = Image.open(io.BytesIO(image_bytes))
        except Exception as e:
            raise Exception(f"Failed to load image from bytes. Error: {e}")

        if image.mode != 'RGB':
            image = image.convert('RGB')

        inputs = self.processor(images=image, return_tensors="pt").to(self.device)
        
        with torch.no_grad():
            image_embeddings = self.model.get_image_features(**inputs)
            
            # Transformers v5 compatibility fix: extract the raw tensor if an object is returned
            if not isinstance(image_embeddings, torch.Tensor):
                image_embeddings = getattr(image_embeddings, 'pooler_output', image_embeddings[0])
            
        return F.normalize(image_embeddings, dim=-1)
