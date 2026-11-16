#include "audio8.hpp"
#include <c10/core/InferenceMode.h>
#include <ATen/Parallel.h>
#include <chrono>
#include <iostream>

int main(int argc,char** argv){
  using namespace audio8;auto began=std::chrono::steady_clock::now();
  try{
    std::unordered_map<std::string,std::string> args;
    for(int i=1;i<argc;++i){std::string key=argv[i];if(key=="--server"||key=="--tokenize"||key=="--no-graphs"||key=="--split-text"||key=="--no-prefix-cache"||key=="--full-vocab"||key=="--legacy-kv"||key=="--dynamic-kv"||key=="--fp16")args[key]="1";else if(i+1<argc)args[key]=argv[++i];else throw std::runtime_error("Missing value for "+key);}
    if(args.count("--split-text")){std::string line;while(std::getline(std::cin,line)){auto request=Json::parse(line);std::cout<<Json{{"sentences",sentences(request.at("text"))}}.dump()<<std::endl;}return 0;}
    if(!args.count("--model"))throw std::runtime_error("Usage: audio8-tts --model DIR --codec FILE (--request JSON | --server | --tokenize)");
    auto directory=std::filesystem::u8path(args.at("--model"));
    if(args.count("--tokenize")){Tokenizer tokenizer(directory/"tokenizer.json");std::string line;while(std::getline(std::cin,line)){auto request=Json::parse(line);std::cout<<Json{{"ids",tokenizer.encode(request.at("text"))}}.dump()<<std::endl;}return 0;}
    c10::InferenceMode mode;at::set_num_threads(4);
    auto emit=[](const Json& j){std::cout<<j.dump()<<std::endl;};emit({{"stage","loading_engine"},{"python_runtime",false}});
    if(!args.count("--codec"))throw std::runtime_error("Missing converted --codec weights");
    Engine engine(directory,std::filesystem::u8path(args.at("--codec")),!args.count("--no-graphs"),emit,!args.count("--no-prefix-cache"),!args.count("--full-vocab"),!args.count("--legacy-kv"),args.count("--dynamic-kv"),args.count("--fp16"));
    emit({{"stage","ready"},{"main_to_ready_seconds",std::chrono::duration<double>(std::chrono::steady_clock::now()-began).count()},{"engine",engine.inspect()}});
    if(args.count("--server")){
      std::string line;while(std::getline(std::cin,line)){try{auto request=Json::parse(line);auto op=request.value("op",std::string("synthesize"));if(op=="quit")break;if(op=="status")emit(engine.inspect());else if(op=="synthesize")engine.synthesize(request);else throw std::runtime_error("Unknown operation");}catch(const std::exception& e){emit({{"stage","error"},{"error",e.what()}});}}return 0;
    }
    if(!args.count("--request"))throw std::runtime_error("Missing --request JSON or --server");
    engine.synthesize(read_json(std::filesystem::u8path(args.at("--request"))));return 0;
  }catch(const std::exception& e){std::cerr<<Json{{"stage","error"},{"error",e.what()}}.dump()<<std::endl;return 1;}
}
