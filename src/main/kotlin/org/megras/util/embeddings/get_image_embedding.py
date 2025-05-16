from transformers import AutoProcessor, AutoModel
from PIL import Image
import torch
import sys

processor = AutoProcessor.from_pretrained("openai/clip-vit-base-patch32")
model = AutoModel.from_pretrained("openai/clip-vit-base-patch32")

def get_image_embeddings(image_path):
    try:
        image = Image.open(image_path)
    except Exception as e:
        print(f"Error opening image: {e}")
        return []  # Return an empty list on error

    inputs = processor(images=image, return_tensors="pt")
    with torch.no_grad():
        image_outputs = model.get_image_features(**inputs)
    return image_outputs.tolist()

if __name__ == "__main__":
    image_file_path = sys.argv[1]
    embedding = get_image_embeddings(image_file_path)
    embedding_string = ",".join(map(str, embedding[0]))
    print(embedding_string)