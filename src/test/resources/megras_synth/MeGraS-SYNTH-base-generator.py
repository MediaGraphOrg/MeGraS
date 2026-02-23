import os
import random
import requests
from tqdm import tqdm

# Configuration
FOLDER_PATH = "val2017"
BASE_URL = "http://localhost:8080"
UPLOAD_ENDPOINT = f"{BASE_URL}/add/file"
SPARQL_ENDPOINT = f"{BASE_URL}/query/sparql"
SAMPLE_SIZE = 1000
SEED = 42

def process_reproducibly():
    # 1. Reproducible Selection
    try:
        all_files = sorted([
            f for f in os.listdir(FOLDER_PATH) 
            if f.lower().endswith(('.png', '.jpg', '.jpeg'))
        ])
    except FileNotFoundError:
        return

    if len(all_files) < SAMPLE_SIZE:
        selected_files = all_files
    else:
        random.seed(SEED)
        selected_files = random.sample(all_files, SAMPLE_SIZE)

    # 2. Sequential Execution
    with requests.Session() as session:
        for filename in tqdm(selected_files, desc="Processing Images", unit="img"):
            file_path = os.path.join(FOLDER_PATH, filename)
            
            try:
                # STEP A: POST FILE
                with open(file_path, 'rb') as f:
                    files_payload = {'file': (filename, f, 'image/jpeg')}
                    upload_res = session.post(
                        UPLOAD_ENDPOINT, 
                        files=files_payload, 
                        params={'metaSkip': 'true'}
                    )

                if upload_res.status_code == 200:
                    # Capture the URI/ID from the server response
                    upload_data = upload_res.json()
                    internal_uri = upload_data.get(filename)['value']

                    if internal_uri:
                        # STEP B: TRIGGER SPARQL QUERY
                        query = f"""
                        PREFIX derived: <http://megras.org/derived/>
                        SELECT *
                        WHERE {{
                          <{internal_uri}> derived:clipEmbedding ?o .
                        }}
                        """.strip()                      

                        # We don't need to capture results, just ensure the query hits
                        session.get(SPARQL_ENDPOINT, params={'query': query})
                
            except Exception:
                # Silently continue or use tqdm.write for network errors
                pass

if __name__ == "__main__":
    process_reproducibly()