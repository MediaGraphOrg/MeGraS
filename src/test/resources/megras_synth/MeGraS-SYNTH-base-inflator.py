import csv
import numpy as np
import os
from tqdm import tqdm

# Configuration
INPUT_FILE = "MeGraS-SYNTH-base.tsv"
EMBEDDING_FILE = "MeGraS-SYNTH-embeddings.tsv" 
STRIPPED_BASE_FILE = "MeGraS-SYNTH-base-no-embeddings.tsv" # New file
BASE_PREDICATE = "<http://megras.org/derived/clipEmbedding>"
VECTOR_DIMS = [256, 512, 768, 1024]
TOTAL_IMAGES = 1000

# Target triple counts for the three variants
TARGET_SCALES = {
    "100k": 100_000,
    "1M": 1_000_000,
    "10M": 10_000_000
}

def parse_vector(vec_string):
    """Parses FloatVector string into numpy array."""
    cleaned = vec_string.split(']^^')[0].replace('[', '')
    return np.fromstring(cleaned, sep=',')

def format_vector(vec_array):
    """Formats array back into the required FloatVector string."""
    return f"[{', '.join(map(str, vec_array.tolist()))}]^^FloatVector"

def generate_multi_scale_datasets():
    if not os.path.exists(INPUT_FILE):
        print(f"Error: {INPUT_FILE} not found.")
        return

    subjects_data = {}
    
    # 1. PASS ONE: Extract base vectors for synthetic math
    print("Pass 1: Identifying subjects and base vectors...")
    with open(INPUT_FILE, mode='r', encoding='utf-8') as f:
        reader = csv.DictReader(f, delimiter='\t')
        for row in reader:
            if row['predicate'] == BASE_PREDICATE:
                subjects_data[row['subject']] = parse_vector(row['object'])

    subject_list = list(subjects_data.keys())[:TOTAL_IMAGES]
    subject_set = set(subject_list)

    # 2. FILE INITIALIZATION
    files_to_close = []
    inflated_writers = {}
    
    # Open original embeddings file
    f_orig = open(EMBEDDING_FILE, mode='w', encoding='utf-8', newline='')
    files_to_close.append(f_orig)
    orig_vec_writer = csv.writer(f_orig, delimiter='\t')
    orig_vec_writer.writerow(['subject', 'predicate', 'object'])

    # Open stripped base file
    f_stripped = open(STRIPPED_BASE_FILE, mode='w', encoding='utf-8', newline='')
    files_to_close.append(f_stripped)
    stripped_writer = csv.writer(f_stripped, delimiter='\t')
    stripped_writer.writerow(['subject', 'predicate', 'object'])

    # Open the three variant files
    for label, count in TARGET_SCALES.items():
        filename = f"MeGraS-SYNTH-inflated-{label}.tsv"
        f_variant = open(filename, mode='w', encoding='utf-8', newline='')
        files_to_close.append(f_variant)
        writer = csv.writer(f_variant, delimiter='\t')
        writer.writerow(['subject', 'predicate', 'object'])
        inflated_writers[label] = {
            'writer': writer,
            'noise_count': (count // TOTAL_IMAGES) - 10
        }

    try:
        # 3. PASS TWO: Preserve Original Metadata & Handle Stripped Base
        print("Pass 2: Processing base triples...")
        with open(INPUT_FILE, mode='r', encoding='utf-8') as f_in:
            reader = csv.DictReader(f_in, delimiter='\t')
            for row in reader:
                if row['subject'] in subject_set:
                    if row['predicate'] == BASE_PREDICATE:
                        # Heavy embedding -> Only to the dedicated file
                        orig_vec_writer.writerow([row['subject'], row['predicate'], row['object']])
                    else:
                        # Non-embedding metadata -> To Stripped Base AND all Inflated Variants
                        stripped_writer.writerow([row['subject'], row['predicate'], row['object']])
                        for v in inflated_writers.values():
                            v['writer'].writerow([row['subject'], row['predicate'], row['object']])

        # 4. PASS THREE: Generate Synthetic Data for inflated scales
        for i, subj in enumerate(tqdm(subject_list, desc="Generating Synthetic Data", unit="subj")):
            base_vec = subjects_data[subj]
            
            for label, data in inflated_writers.items():
                writer = data['writer']
                
                # Synthetic Vectors
                for dim in VECTOR_DIMS:
                    predicate = f"<http://megras.org/derived/vec{dim}>"
                    if dim == 512:
                        new_vec = base_vec
                    elif dim < 512:
                        new_vec = base_vec[:dim]
                    else:
                        padding_needed = dim - len(base_vec)
                        np.random.seed(hash(subj) % 2**32)
                        noise = np.random.normal(0, 0.01, padding_needed)
                        new_vec = np.concatenate([base_vec, noise])
                    writer.writerow([subj, predicate, format_vector(new_vec)])

                # Selectivity Predicates
                for limit, tag in [(1, "001"), (10, "01"), (100, "1"), (500, "5")]:
                    if i < limit:
                        writer.writerow([subj, f"<http://megras.org/synth#sel{tag}>", "true^^String"])

                # Volume Inflation
                for j in range(data['noise_count']):
                    writer.writerow([subj, f"<http://megras.org/synth#prop_{j}>", f"val_{j}^^String"])

    finally:
        for f in files_to_close:
            f.close()

    print(f"\nProcessing Complete.")
    print(f" - {EMBEDDING_FILE}: Just the clipEmbeddings.")
    print(f" - {STRIPPED_BASE_FILE}: Base metadata only (no embeddings).")
    for label in TARGET_SCALES:
        print(f" - MeGraS-SYNTH-inflated-{label}.tsv: Full inflated variant.")

if __name__ == "__main__":
    generate_multi_scale_datasets()