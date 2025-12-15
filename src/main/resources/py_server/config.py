
import os

class Config():

    # The singleton instance variable
    _INSTANCE = None
   
    @classmethod
    def getInstance(cls):
        """Used to obtain the singleton instance"""
        if cls._INSTANCE == None:
            cls._INSTANCE = cls._CFG()
        return cls._INSTANCE

    class _CFG():

        def __init__(self):
            self.working_directory = os.environ.get("LICENSE_TOOL_PROJECT" or os.getcwd())

        def getCurrentWorkingDirectory(self):
            return self.working_directory

        def setCurrentWorkingDirectory(self, directory):
            self.working_directory = directory

CONFIG = Config.getInstance()