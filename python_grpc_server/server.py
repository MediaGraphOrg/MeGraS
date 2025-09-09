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
from trocr_ocr import TrOCROCR
from docling_extractor import DoclingExtractor

_ONE_DAY_IN_SECONDS = 60 * 60 * 24

# Load configuration from config.json
CONFIG_PATH = os.path.join(os.path.dirname(__file__), 'config.json')
with open(CONFIG_PATH, 'r') as f:
    config = json.load(f)

class ClipServiceServicer(clip_service_pb2_grpc.ClipServiceServicer):
    """
    Implements the gRPC methods for the ClipService.
    """
    def __init__(self, device, model_name):
        self.clip_embedder = CLIPEmbedder(device=device, model_name=model_name)
        print(f"CLIPEmbedder initialized with model {model_name} on {device}.")

    def GetTextEmbedding(self, request, context):
        """
        Handles GetTextEmbedding RPC.
        """
        try:
            text_embedding = self.clip_embedder.get_text_embeddings(request.text)
            # Convert torch tensor to a list of floats for Protobuf
            return clip_service_pb2.EmbeddingResponse(embedding=text_embedding.squeeze().tolist())
        except Exception as e:
            context.set_details(f"Error getting text embedding: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return clip_service_pb2.EmbeddingResponse() # Return an empty response on error

    def GetImageEmbedding(self, request, context):
        """
        Handles GetImageEmbedding RPC.
        """
        try:
            # Pass the image_data bytes directly to the embedder
            image_embedding = self.clip_embedder.get_image_embeddings(request.image_data)
            # Convert torch tensor to a list of floats for Protobuf
            return clip_service_pb2.EmbeddingResponse(embedding=image_embedding.squeeze().tolist())
        except Exception as e:
            context.set_details(f"Error getting image embedding: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return clip_service_pb2.EmbeddingResponse() # Return an empty response on error

class OcrServiceServicer(ocr_service_pb2_grpc.OcrServiceServicer):
    """
    Implements the gRPC methods for the OCRService.
    """
    def __init__(self, device, model_name):
        # Initialize TrOCROCR with a suitable device and model for lifelog/printed text
        self.trocr_ocr = TrOCROCR(device=device, model_name=model_name)
        print(f"TrOCROCR initialized with model {model_name} on {device}.")

    def RecognizeText(self, request, context):
        """
        Handles RecognizeText RPC for OCR.
        """
        try:
            recognized_text = self.trocr_ocr.recognize_text(request.image_data)
            return ocr_service_pb2.RecognizeTextResponse(recognized_text=recognized_text)
        except Exception as e:
            context.set_details(f"Error recognizing text: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return ocr_service_pb2.RecognizeTextResponse() # Return an empty response on error


class DoclingServiceServicer(docling_service_pb2_grpc.DoclingServiceServicer):
    """Implements DoclingService for PDF content extraction."""

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

    def ExtractFigures(self, request, context):
        try:
            figures = self.extractor.extract_figures(request.pdf_data)
            return docling_service_pb2.ExtractFiguresResponse(figures_json=json.dumps(figures))
        except Exception as e:
            context.set_details(f"Error extracting figures: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return docling_service_pb2.ExtractFiguresResponse()

    def ExtractTables(self, request, context):
        try:
            tables = self.extractor.extract_tables(request.pdf_data)
            return docling_service_pb2.ExtractTablesResponse(tables_json=json.dumps(tables))
        except Exception as e:
            context.set_details(f"Error extracting tables: {e}")
            context.set_code(grpc.StatusCode.INTERNAL)
            return docling_service_pb2.ExtractTablesResponse()


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))

    # Add CLIP Service
    clip_service_pb2_grpc.add_ClipServiceServicer_to_server(
        ClipServiceServicer(device=config["device"], model_name=config["clip_embedder_model"]), server)

    # Add OCR Service
    ocr_service_pb2_grpc.add_OcrServiceServicer_to_server(
        OcrServiceServicer(device=config["device"], model_name=config["trocr_ocr_model"]), server)

    # Add Docling Service (threads and langs optional in config)
    docling_service_pb2_grpc.add_DoclingServiceServicer_to_server(
        DoclingServiceServicer(), server)

    server.add_insecure_port('[::]:50051') # Listen on all interfaces, port 50051
    print("Starting gRPC server on port 50051...")
    server.start()
    try:
        while True:
            time.sleep(_ONE_DAY_IN_SECONDS)
    except KeyboardInterrupt:
        server.stop(0)
        print("gRPC server stopped.")

if __name__ == '__main__':
    serve()