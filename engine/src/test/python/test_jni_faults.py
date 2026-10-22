import shutil,subprocess,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4]
class JniAllocationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build=ROOT/'.build/jni-fault'; cls.build.mkdir(parents=True,exist_ok=True)
        for source in (ROOT/'engine/src/test/cpp/jni-shim').iterdir():shutil.copy2(source,cls.build/source.name)
        shutil.copy2(ROOT/'engine/src/main/cpp/jni_bridge.cpp',cls.build/'jni_under_test.cpp')
        subprocess.run(['g++','-std=c++17','-O0','-g','-I',str(cls.build),str(cls.build/'fault_test.cpp'),'-o',str(cls.build/'fault_test')],check=True,capture_output=True)
    def check_case(self,name):
        p=subprocess.run([str(self.build/'fault_test'),name],capture_output=True,text=True)
        self.assertEqual(0,p.returncode,p.stdout+p.stderr)
    def test_array_failure(self):self.check_case('array')
    def test_string_failure(self):self.check_case('string')
    def test_utf_failure_keeps_pending_exception(self):self.check_case('utf')
    def test_global_reference_failure_does_not_search(self):self.check_case('global')
    def test_callback_class_failure(self):self.check_case('class')
    def test_callback_string_failure(self):self.check_case('callback-string')
    def test_bad_alloc_is_typed(self):self.check_case('badalloc')
    def test_utf_released_on_cpp_allocation_failure(self):self.check_case('utf-release')
