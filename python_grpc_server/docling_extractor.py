import json
import logging
import tempfile
from pathlib import Path
from typing import List, Dict, Any, Optional

from docling.datamodel.accelerator_options import AcceleratorDevice, AcceleratorOptions
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions
from docling.document_converter import DocumentConverter, PdfFormatOption

_log = logging.getLogger(__name__)


class DoclingExtractor:
    """
    Extracts text, figures, and tables from PDF bytes using the Docling pipeline.
    The heavy DocumentConverter is created once and reused across calls.
    """

    def __init__(self, num_threads: int = 4, ocr_langs: Optional[List[str]] = None):
        if ocr_langs is None:
            ocr_langs = ["en"]

        pipeline_options = PdfPipelineOptions()
        pipeline_options.do_ocr = True
        pipeline_options.do_table_structure = True
        pipeline_options.table_structure_options.do_cell_matching = True
        pipeline_options.ocr_options.lang = ocr_langs
        pipeline_options.accelerator_options = AcceleratorOptions(
            num_threads=num_threads, device=AcceleratorDevice.AUTO
        )

        self._converter = DocumentConverter(
            format_options={
                InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options)
            }
        )

    def _convert_bytes(self, pdf_bytes: bytes):
        """
        Runs conversion by writing bytes to a temporary PDF and invoking the converter.
        Returns the conversion result object.
        """
        if not pdf_bytes:
            raise ValueError("Empty PDF data provided.")

        # Write to a temporary file so Docling can read it.
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
            tmp.write(pdf_bytes)
            tmp_path = Path(tmp.name)

        try:
            conv_result = self._converter.convert(tmp_path)
            return conv_result
        finally:
            try:
                tmp_path.unlink(missing_ok=True)
            except Exception:
                # Best-effort cleanup; ignore removal errors
                pass

    def extract_text(self, pdf_bytes: bytes) -> str:
        conv_result = self._convert_bytes(pdf_bytes)
        return conv_result.document.export_to_text()

    def _export_dict(self, pdf_bytes: bytes) -> Dict[str, Any]:
        conv_result = self._convert_bytes(pdf_bytes)
        return conv_result.document.export_to_dict()

    def extract_figures(self, pdf_bytes: bytes) -> List[Dict[str, Any]]:
        data = self._export_dict(pdf_bytes)
        figures = []
        if isinstance(data, dict) and "pictures" in data and isinstance(data["pictures"], list):
            figures = list(data["pictures"])  # shallow copy
        return figures

    def extract_tables(self, pdf_bytes: bytes) -> List[Dict[str, Any]]:
        data = self._export_dict(pdf_bytes)
        tables = []
        if isinstance(data, dict) and "tables" in data and isinstance(data["tables"], list):
            tables = list(data["tables"])  # shallow copy
        return tables
