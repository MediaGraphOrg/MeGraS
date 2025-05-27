from transformers import AutoProcessor, AutoModel
import torch
import sys

processor = AutoProcessor.from_pretrained("openai/clip-vit-base-patch32")
model = AutoModel.from_pretrained("openai/clip-vit-base-patch32")

def get_text_embeddings(text):
    inputs = processor(text=text, return_tensors="pt")
    with torch.no_grad():
        text_outputs = model.get_text_features(**inputs)
    return text_outputs.tolist()

if __name__ == "__main__":
    text_to_embed = sys.argv[1]
    embedding = get_text_embeddings(text_to_embed)
    # Convert the embedding list to a comma-separated string for easier parsing in Kotlin
    embedding_string = ",".join(map(str, embedding[0]))  # Assuming you want the first element of the batch
    print(embedding_string)