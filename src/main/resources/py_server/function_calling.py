
from model_functions import get_dependency_license, get_licensing_for_my_project, get_dependency_list

## Dictionary of functions that the model can call
TOOL_MAPPING = {
    "get_dependency_license": get_dependency_license,
    "get_licensing_for_my_project": get_licensing_for_my_project,
    "get_dependency_list": get_dependency_list
    }

REVERSE_MAPPING = {v:k for k,v in TOOL_MAPPING.items()}

## Parameter names and order for each function
PARAMETER_ORDERS = {
    "get_dependency_license": ("dependency",),
    "get_licensing_for_my_project": (),
    "get_dependency_list": (), 
}

## Schema given to the model
TOOLS_SCHEMA = [
    {
        "type": "function",
        "function": {
            "name": "get_dependency_license",
            "description": "A function that returns the licensing information for a provided dependency.",
            "parameters": {
                "type" : "object",
                "properties": {
                    "dependency": {
                        'type': 'string',
                        'description': 'The dependency whose licensing information we are looking for. Written as one word.  Spaces should be replaced with hyphens.',
                    }
                }
            }
        }
    },
    {
    "type": "function",
        "function": {
            "name": "get_licensing_for_my_project",
            "description": "A function that returns the licensing information for the developer's project.",
        }
    },
    {
    "type": "function",
        "function": {
            "name": "get_dependency_list",
            "description": "A function that returns the list of dependencies included in the developer's project.",
        }
    },
]

def getFunctionArguments(func, parameters):
    args = []
    for p in PARAMETER_ORDERS[func]:
        args.append(parameters[p])
    return args