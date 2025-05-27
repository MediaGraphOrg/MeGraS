import grpc
import time
from concurrent import futures

# Import the generated gRPC files
import clip_service_pb2
import clip_service_pb2_grpc

# Import your CLIP Embedder class
from clip_embedder import CLIPEmbedder

_ONE_DAY_IN_SECONDS = 60 * 60 * 24

class ClipServiceServicer(clip_service_pb2_grpc.ClipServiceServicer):
    """
    Implements the gRPC methods for the ClipService.
    """
    def __init__(self):
        self.clip_embedder = CLIPEmbedder(device="cpu") # or "cuda" if GPU is available
        print("CLIPEmbedder initialized.")

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

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    clip_service_pb2_grpc.add_ClipServiceServicer_to_server(
        ClipServiceServicer(), server)
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