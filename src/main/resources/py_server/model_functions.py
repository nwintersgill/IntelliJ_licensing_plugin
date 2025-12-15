
"""
This file defines functions that are callable by the GenAI model.
"""

import os, glob, spdx_matcher
from config import CONFIG
import xml.etree.ElementTree as ET

def get_dependency_list():
    cwd = CONFIG.getCurrentWorkingDirectory()
    bom_path = os.path.join(cwd, ".license-tool", "bom.xml")
    try:
        tree = ET.parse(bom_path)
        strip_namespace(tree)
        root = tree.getroot()
        components = root.find("components")
        if components:
            deps = [component.find("name").text for component in components]
            return "Dependencies used: " + ", ".join(deps)
        else: return "This project has no listed dependencies"
    except ET.ParseError as e:
        return f"Error parsing XML file: {e}"

def get_licensing_for_my_project():
    cwd = CONFIG.getCurrentWorkingDirectory()
    license_files = glob.glob(os.path.join(cwd, "LICENSE*"))
    project_licenses = []
    for i, lic in enumerate(license_files):
        with open(lic, "r", encoding="utf-8") as file:
            license_text = file.read()
            licenses, confidence = spdx_matcher.analyse_license_text(license_text)
            project_licenses.extend(licenses.get("licenses").keys())
    if not project_licenses:
        return "I could not find any licensing information for this project."
    return "The licensing for this project is: " + ", ".join(project_licenses)

def get_dependency_license(dependency):
    cwd = CONFIG.getCurrentWorkingDirectory()
    bom_path = os.path.join(cwd, ".license-tool", "bom.xml")
    try:
        tree = ET.parse(bom_path)
        strip_namespace(tree)
        root = tree.getroot()
        components = root.find("components")
        if components:
            for component in components:
                if dependency.lower() == component.find("name").text:
                    licenses = component.find("licenses")
                    if licenses:
                        return ET.tostring(licenses, encoding='unicode')
                    else:
                        return "No licensing information supplied"
        return f"Dependency not found, {bom_path}"
    except ET.ParseError as e:
        return f"Error parsing XML file: {e}"

def strip_namespace(tree):
    for elem in tree.iter():
        if '}' in elem.tag:
            elem.tag = elem.tag.split('}', 1)[1]