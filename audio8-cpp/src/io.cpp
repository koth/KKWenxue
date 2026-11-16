#include "audio8.hpp"
#include <fstream>
#include <cstring>
#include <limits>
#include <cmath>
#include <sstream>
#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace audio8 {
std::string read_text(const Path& path) {
  std::ifstream f(path,std::ios::binary);
  if(!f) throw std::runtime_error("Cannot read " + path.u8string());
  std::string s((std::istreambuf_iterator<char>(f)),{});
  if(s.size()>=3 && s.substr(0,3)=="\xef\xbb\xbf") s.erase(0,3);
  return s;
}
Json read_json(const Path& p) {return Json::parse(read_text(p));}
void write_json(const Path& p,const Json& j) {
  std::ofstream f(p,std::ios::binary); if(!f) throw std::runtime_error("Cannot write JSON");
  f<<j.dump(2); if(!f) throw std::runtime_error("JSON write failed");
}
class MappedFile {
 public:
  const char* data=nullptr; uint64_t size=0;
#ifdef _WIN32
  HANDLE file=INVALID_HANDLE_VALUE, mapping=nullptr;
  explicit MappedFile(const Path& p) {
    file=CreateFileW(p.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot open weights");
    LARGE_INTEGER n; GetFileSizeEx(file,&n); size=n.QuadPart;
    mapping=CreateFileMappingW(file,nullptr,PAGE_READONLY,0,0,nullptr);
    if(mapping) data=static_cast<const char*>(MapViewOfFile(mapping,FILE_MAP_READ,0,0,0));
    if(!data) {if(mapping)CloseHandle(mapping);CloseHandle(file);throw std::runtime_error("Cannot map weights");}
  }
  ~MappedFile(){if(data)UnmapViewOfFile(data);if(mapping)CloseHandle(mapping);if(file!=INVALID_HANDLE_VALUE)CloseHandle(file);}
#else
  int fd=-1;
  explicit MappedFile(const Path& p) {
    fd=open(p.c_str(),O_RDONLY);if(fd<0)throw std::runtime_error("Cannot open weights");
    struct stat st; if(fstat(fd,&st)){close(fd);throw std::runtime_error("Cannot stat weights");} size=st.st_size;
    void* address=mmap(nullptr,size,PROT_READ,MAP_PRIVATE,fd,0);
    if(address==MAP_FAILED){close(fd);throw std::runtime_error("Cannot map weights");}data=static_cast<const char*>(address);
  }
  ~MappedFile(){if(data)munmap(const_cast<char*>(data),size);if(fd>=0)close(fd);}
#endif
};
Weights load_safetensors(const Path& path,const at::Device& device) {
  MappedFile f(path);if(f.size<8)throw std::runtime_error("Truncated safetensors file");
  uint64_t bytes;std::memcpy(&bytes,f.data,8);
  if(bytes>100000000 || bytes>f.size-8)throw std::runtime_error("Invalid safetensors header");
  Json header=Json::parse(f.data+8,f.data+8+bytes);Weights result;
  for(auto it=header.begin();it!=header.end();++it){
    if(it.key()=="__metadata__")continue;
    auto shape=it.value().at("shape").get<std::vector<int64_t>>();
    auto offsets=it.value().at("data_offsets").get<std::vector<uint64_t>>();
    std::string kind=it.value().at("dtype");at::ScalarType dtype;
    if(kind=="BF16")dtype=at::kBFloat16;else if(kind=="F16")dtype=at::kHalf;else if(kind=="F32")dtype=at::kFloat;else throw std::runtime_error("Unsupported weight dtype: "+kind);
    uint64_t count=1;for(auto d:shape){if(d<0 || (d && count>std::numeric_limits<uint64_t>::max()/d))throw std::runtime_error("Invalid weight shape");count*=d;}
    auto expected=count*c10::elementSize(dtype);
    if(offsets.size()!=2 || offsets[1]<offsets[0] || offsets[1]>f.size-8-bytes || offsets[1]-offsets[0]!=expected)throw std::runtime_error("Invalid safetensors offsets");
    auto cpu=at::from_blob(const_cast<char*>(f.data+8+bytes+offsets[0]),shape,at::TensorOptions().dtype(dtype));
    result.emplace(it.key(),device.is_cuda()?cpu.to(device):cpu.clone());
  }
  // Synchronous per-tensor transfers finish before the memory mapping is freed.
  return result;
}
void write_wav(const Path& path,const Tensor& waveform,int rate){
  auto x=waveform.detach().flatten().to(at::kCPU,at::kFloat).contiguous();
  if(x.numel()>(UINT32_MAX-36)/2)throw std::runtime_error("WAV exceeds RIFF size");
  std::ofstream f(path,std::ios::binary);if(!f)throw std::runtime_error("Cannot create WAV");
  auto u16=[&](uint16_t v){f.put(v&255);f.put(v>>8);};auto u32=[&](uint32_t v){u16(v&65535);u16(v>>16);};
  f.write("RIFF",4);u32(36+x.numel()*2);f.write("WAVEfmt ",8);u32(16);u16(1);u16(1);u32(rate);u32(rate*2);u16(2);u16(16);f.write("data",4);u32(x.numel()*2);
  auto data=x.data_ptr<float>();for(int64_t i=0;i<x.numel();++i){float v=std::max(-1.f,std::min(0.999969482421875f,data[i]));u16(static_cast<uint16_t>(static_cast<int16_t>(std::floor(v*32768.f))));}
  if(!f)throw std::runtime_error("WAV write failed");
}
void write_npy(const Path& path,const Tensor& tensor){
  auto x=tensor.detach().to(at::kCPU,at::kLong).contiguous();std::string shape;
  for(auto d:x.sizes())shape+=std::to_string(d)+", ";
  std::string header="{'descr': '<i8', 'fortran_order': False, 'shape': ("+shape+"), }";
  header.append((64-(10+header.size()+1)%64)%64,' ');header+='\n';
  std::ofstream f(path,std::ios::binary);if(!f)throw std::runtime_error("Cannot create NPY");
  f.write("\x93NUMPY\x01\x00",8);uint16_t n=static_cast<uint16_t>(header.size());f.put(n&255);f.put(n>>8);f.write(header.data(),header.size());f.write(reinterpret_cast<const char*>(x.data_ptr<int64_t>()),x.numel()*8);
  if(!f)throw std::runtime_error("NPY write failed");
}
Tensor read_npy(const Path& path){
  auto s=read_text(path);if(s.size()<10 || s.substr(0,8)!=std::string("\x93NUMPY\x01\x00",8))throw std::runtime_error("Expected NPY v1 int64 codes");
  size_t h=static_cast<unsigned char>(s[8])+(static_cast<unsigned char>(s[9])<<8);
  if(h>s.size()-10)throw std::runtime_error("Truncated NPY");std::string header=s.substr(10,h);
  if(header.find("'<i8'")==std::string::npos || header.find("False")==std::string::npos)throw std::runtime_error("Expected little-endian C-order int64 codes");
  auto begin=header.find('('),end=header.find(')',begin);std::vector<int64_t> shape;
  if(begin==std::string::npos || end==std::string::npos)throw std::runtime_error("Invalid NPY shape");
  std::istringstream stream(header.substr(begin+1,end-begin-1));int64_t dim;char separator;
  while(stream>>dim){if(dim<0 || dim>10000000)throw std::runtime_error("Invalid NPY dimension");shape.push_back(dim);stream>>separator;}
  int64_t count=1;for(auto n:shape){if(n && count>10000000/n)throw std::runtime_error("NPY too large");count*=n;}
  if(s.size()-10-h!=count*8)throw std::runtime_error("NPY byte length mismatch");
  return at::from_blob(s.data()+10+h,shape,at::TensorOptions().dtype(at::kLong)).clone();
}
void write_float_raw(const Path& path,const Tensor& tensor){
  auto x=tensor.detach().to(at::kCPU,at::kFloat).contiguous();std::ofstream f(path,std::ios::binary);if(!f)throw std::runtime_error("Cannot write diagnostic tensor");f.write(reinterpret_cast<const char*>(x.data_ptr<float>()),x.numel()*4);
}
} // namespace audio8
