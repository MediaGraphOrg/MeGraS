# MeGraS-SYNTH Dataset

Synthetic benchmark dataset for evaluating MeGraS performance at varying scales.

## Overview

MeGraS-SYNTH is derived from the [COCO 2017 Validation Set](https://cocodataset.org/) and consists of:
- **Base dataset**: 1,000 randomly sampled images with CLIP embeddings computed via MeGraS
- **Inflated variants**: Scaled datasets (100K, 1M, 10M triples) for ingestion and query benchmarks

## Files

| File | Description |
|------|-------------|
| `MeGraS-SYNTH-base-generator.py` | Uploads COCO images to MeGraS and triggers CLIP embedding extraction |
| `MeGraS-SYNTH-base.tsv` | Pre-generated base triples (1,000 images) — ready to use |
| `MeGraS-SYNTH-base-inflator.py` | Generates scaled variants from the base dataset |

## Quick Start (Using Pre-generated Data)

If you want to skip the image processing step, use the provided `MeGraS-SYNTH-base.tsv`:

```bash
cd src/test/resources/megras_synth
pip install numpy tqdm
python MeGraS-SYNTH-base-inflator.py
```

This generates all inflated variants directly from the cached base file.

## Full Reproduction (From COCO Images)

### Prerequisites
- Python 3.8+
- Running MeGraS instance with Python gRPC server (for CLIP embeddings)
- COCO val2017 images

### Step 1: Download COCO val2017
```bash
wget http://images.cocodataset.org/zips/val2017.zip
unzip val2017.zip -d src/test/resources/megras_synth/
```

### Step 2: Start MeGraS
```bash
./gradlew run
```

### Step 3: Generate Base Dataset
```bash
cd src/test/resources/megras_synth
pip install requests tqdm numpy
python MeGraS-SYNTH-base-generator.py
```

This uploads 1,000 randomly sampled images to MeGraS and triggers CLIP embedding computation for each.

### Step 4: Export Triples
Export the generated triples from MeGraS to `MeGraS-SYNTH-base.tsv`:
```
GET http://localhost:8080/dump
```

### Step 5: Generate Inflated Variants
```bash
python MeGraS-SYNTH-base-inflator.py
```

## Output Files

| File | Description |
|------|-------------|
| `MeGraS-SYNTH-embeddings.tsv` | Original CLIP embeddings only |
| `MeGraS-SYNTH-base-no-embeddings.tsv` | Base metadata without embeddings |
| `MeGraS-SYNTH-inflated-100k.tsv` | 100,000 triples |
| `MeGraS-SYNTH-inflated-1M.tsv` | 1,000,000 triples |
| `MeGraS-SYNTH-inflated-10M.tsv` | 10,000,000 triples |

## Reproducibility Parameters

| Parameter | Value |
|-----------|-------|
| Random seed | 42 |
| Sample size | 1,000 images |
| Source dataset | COCO val2017 (5,000 images) |
| Embedding model | CLIP ViT-B/32 (via MeGraS) |
| Base embedding dimension | 512 |

## Synthetic Data Characteristics

The inflator generates additional properties for each subject:

### Multi-dimensional Vectors
Synthetic vector predicates at different dimensions for benchmarking:
- `vec256`: First 256 dimensions of CLIP embedding
- `vec512`: Original CLIP embedding (512-d)
- `vec768`: CLIP + 256-d Gaussian noise padding
- `vec1024`: CLIP + 512-d Gaussian noise padding

### Selectivity Predicates
Predicates with controlled cardinality for query selectivity experiments:
- `sel001`: 1 subject (0.1% selectivity)
- `sel01`: 10 subjects (1% selectivity)
- `sel1`: 100 subjects (10% selectivity)
- `sel5`: 500 subjects (50% selectivity)

### Volume Padding
Synthetic `prop_N` predicates to reach target triple counts without affecting query semantics.

## Requirements

```
requests>=2.28.0
tqdm>=4.64.0
numpy>=1.21.0
```

