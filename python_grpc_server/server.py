import grpc
import time
from concurrent import futures
import sys
import os
import json

# Add the 'generated' directory to the Python path
sys.path.append(os.path.join(os.path.dirname(__file__), 'generated'))

# Import the generated gRPC files
import clip_service_pb2
import clip_service_pb2_grpc
import ocr_service_pb2
import ocr_service_pb2_grpc
import docling_service_pb2
import docling_service_pb2_grpc

# Import service classes
from clip_embedder import CLIPEmbedder
from siglip_embedder import SigLIPEmbedder  # <-- New Import
from trocr_ocr import TrOCROCR
from docling_extractor import DoclingExtractor

_ONE_DAY_IN_SECONDS = 60 * 60 * 24

# Load configuration from config.json
CONFIG_PATH = os.path.join(os.path.dirname(__file__), 'config.json')
with open(CONFIG_PATH, 'r') as f:
    config = json.load(f)

GRPC_HOST = config.get("grpc_host", "0.0.0.0")
GRPC_PORT = int(config.get("grpc_port", 50051))

class ClipServiceServicer(clip_service_pb2_grpc.ClipServiceServicer):
    def __init__(self, device, model_name):
        self.clip_embedder = CLIPEmbedder(device=device, model_name=model_name)
        print(f"CLIPEmbedder initialized with model {model_name} on {device}.")

    def GetTextEmbedding(self, request, context):
        try:
            text_embedding = self.clip_embedder.get_text_embeddings(request.text)
            vec = text_embedding.squeeze().tolist()
            return clip_service_pb2.EmbeddingResponse(embedding=vec)
        except Exception as e:
            context.set_details(f"Error getting text embedding: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return clip_service_pb2.EmbeddingResponse()

    def GetImageEmbedding(self, request, context):
        try:
            image_embedding = self.clip_embedder.get_image_embeddings(request.image_data)
            return clip_service_pb2.EmbeddingResponse(embedding=image_embedding.squeeze().tolist())
        except Exception as e:
            context.set_details(f"Error getting image embedding: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return clip_service_pb2.EmbeddingResponse()

class SiglipServiceServicer(clip_service_pb2_grpc.SiglipServiceServicer):
    """Implements the gRPC methods for the SiglipService."""
    def __init__(self, device, model_name):
        self.siglip_embedder = SigLIPEmbedder(device=device, model_name=model_name)
        print(f"SigLIPEmbedder initialized with model {model_name} on {device}.")

    def GetTextEmbedding(self, request, context):
        try:
            text_embedding = self.siglip_embedder.get_text_embeddings(request.text)
            return clip_service_pb2.EmbeddingResponse(embedding=text_embedding.squeeze().tolist())
        except Exception as e:
            context.set_details(f"Error getting SigLIP text embedding: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return clip_service_pb2.EmbeddingResponse()

    def GetImageEmbedding(self, request, context):
        try:
            image_embedding = self.siglip_embedder.get_image_embeddings(request.image_data)
            return clip_service_pb2.EmbeddingResponse(embedding=image_embedding.squeeze().tolist())
        except Exception as e:
            context.set_details(f"Error getting SigLIP image embedding: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return clip_service_pb2.EmbeddingResponse()

class OcrServiceServicer(ocr_service_pb2_grpc.OcrServiceServicer):
    def __init__(self, device, model_name):
        self.trocr_ocr = TrOCROCR(device=device, model_name=model_name)
        print(f"TrOCROCR initialized with model {model_name} on {device}.")

    def RecognizeText(self, request, context):
        try:
            recognized_text = self.trocr_ocr.recognize_text(request.image_data)
            return ocr_service_pb2.RecognizeTextResponse(recognized_text=recognized_text)
        except Exception as e:
            context.set_details(f"Error recognizing text: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return ocr_service_pb2.RecognizeTextResponse()


class DoclingServiceServicer(docling_service_pb2_grpc.DoclingServiceServicer):
    def __init__(self, num_threads: int = 4, ocr_langs=None):
        self.extractor = DoclingExtractor(num_threads=num_threads, ocr_langs=ocr_langs or ["en"])
        print(f"DoclingExtractor initialized with {num_threads} threads and langs {ocr_langs or ['en']}.")

    def ExtractText(self, request, context):
        try:
            text = self.extractor.extract_text(request.pdf_data)
            return docling_service_pb2.ExtractTextResponse(text=text)
        except Exception as e:
            context.set_details(f"Error extracting text: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return docling_service_pb2.ExtractTextResponse()

    def ExtractDocJson(self, request, context):
        try:
            json_str = self.extractor.export_json(request.pdf_data)
            return docling_service_pb2.ExtractDocJsonResponse(json=json_str)
        except Exception as e:
            context.set_details(f"Error extracting Docling JSON: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return docling_service_pb2.ExtractDocJsonResponse()


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))

    # Add CLIP Service
    clip_service_pb2_grpc.add_ClipServiceServicer_to_server(
        ClipServiceServicer(device=config["device"], model_name=config["clip_embedder_model"]), server)

    # Add SigLIP Service
    siglip_model = config.get("siglip_embedder_model", "google/siglip-so400m-patch14-384")
    clip_service_pb2_grpc.add_SiglipServiceServicer_to_server(
        SiglipServiceServicer(device=config["device"], model_name=siglip_model), server)

    # Add OCR Service
    ocr_service_pb2_grpc.add_OcrServiceServicer_to_server(
        OcrServiceServicer(device=config["device"], model_name=config["trocr_ocr_model"]), server)

    # Add Docling Service
    docling_threads = config.get("docling_threads", 4)
    docling_langs = config.get("docling_ocr_langs", ["en"])
    docling_service_pb2_grpc.add_DoclingServiceServicer_to_server(
        DoclingServiceServicer(num_threads=docling_threads, ocr_langs=docling_langs), server)

    bind_addr = f"{GRPC_HOST}:{GRPC_PORT}"
    server.add_insecure_port(bind_addr)
    print(f"Starting gRPC server on {bind_addr}...")
    server.start()
    try:
        while True:
            time.sleep(_ONE_DAY_IN_SECONDS)
    except KeyboardInterrupt:
        server.stop(0)
        print("gRPC server stopped.")

if __name__ == '__main__':
    serve()
