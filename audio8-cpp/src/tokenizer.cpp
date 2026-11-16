#include "audio8.hpp"
#include <pcre2.h>
#include <utf8proc.h>
#include <climits>

namespace audio8 {
static std::string utf8(int code){
  char bytes[4];int n=utf8proc_encode_char(code,reinterpret_cast<utf8proc_uint8_t*>(bytes));return std::string(bytes,n);
}
static std::string nfc(const std::string& text){
  utf8proc_uint8_t* out=nullptr;
  auto n=utf8proc_map(reinterpret_cast<const utf8proc_uint8_t*>(text.data()),text.size(),&out,static_cast<utf8proc_option_t>(UTF8PROC_STABLE|UTF8PROC_COMPOSE));
  if(n<0)throw std::runtime_error("Invalid UTF-8 text");std::string s(reinterpret_cast<char*>(out),n);free(out);return s;
}
std::string clean_text(const std::string& s){
  std::string out;bool space=false;size_t i=0;
  while(i<s.size()){
    int32_t code;auto n=utf8proc_iterate(reinterpret_cast<const unsigned char*>(s.data()+i),s.size()-i,&code);
    if(n<0)throw std::runtime_error("Invalid UTF-8 text");
    bool ws=(code>=9&&code<=13)||(code>=28&&code<=32)||code==0x85||code==0xa0||code==0x1680||(code>=0x2000&&code<=0x200a)||code==0x2028||code==0x2029||code==0x202f||code==0x205f||code==0x3000;
    if(ws){space=!out.empty();}else{if(space)out+=' ';space=false;out.append(s,i,n);}i+=n;
  }return out;
}
std::vector<std::string> sentences(const std::string& s){
  std::vector<std::string> result;size_t begin=0,i=0;
  auto terminal=[](int32_t code){return code==0x3002||code==0xff01||code==0xff1f||code==0x2026||code=='!'||code=='?';};
  auto closer=[](int32_t code){return code==0x201d||code==0x2019||code==0x300d||code==0x300f||code==0x300b||code==0x3009||code==0xff09||code==')'||code==']'||code=='}'||code==0x3011||code==0x3015;};
  auto append=[&](const std::string& part){
    bool spoken=false;size_t at=0;
    while(at<part.size()){int32_t code;auto n=utf8proc_iterate(reinterpret_cast<const unsigned char*>(part.data()+at),part.size()-at,&code);if(n<0)throw std::runtime_error("Invalid UTF-8 text");auto category=utf8proc_category(code);spoken=spoken||(category>=UTF8PROC_CATEGORY_LU&&category<=UTF8PROC_CATEGORY_LO)||(category>=UTF8PROC_CATEGORY_ND&&category<=UTF8PROC_CATEGORY_NO);at+=n;}
    if(spoken)result.push_back(part);else if(!result.empty())result.back()+=part;
  };
  while(i<s.size()){
    int32_t code;auto n=utf8proc_iterate(reinterpret_cast<const unsigned char*>(s.data()+i),s.size()-i,&code);if(n<0)throw std::runtime_error("Invalid UTF-8 text");i+=n;
    if(terminal(code)){
      while(i<s.size()){int32_t next;auto width=utf8proc_iterate(reinterpret_cast<const unsigned char*>(s.data()+i),s.size()-i,&next);if(width<0)throw std::runtime_error("Invalid UTF-8 text");if(!terminal(next)&&!closer(next))break;i+=width;}
      append(s.substr(begin,i-begin));begin=i;
    }
  }if(begin<s.size())append(s.substr(begin));return result;
}
Tokenizer::Tokenizer(const Path& path){
  auto j=read_json(path);if(j["normalizer"]["type"]!="NFC" || j["model"]["type"]!="BPE")throw std::runtime_error("Unsupported tokenizer format");
  auto& m=j["model"];vocab_=m["vocab"].get<decltype(vocab_)>();
  int rank=0;for(auto& pair:m["merges"]){ranks_.emplace(pair[0].get<std::string>()+std::string(1,'\0')+pair[1].get<std::string>(),rank++);}
  for(auto& t:j["added_tokens"]){if(!t["single_word"].get<bool>()&&!t["lstrip"].get<bool>()&&!t["rstrip"].get<bool>())specials_[t["content"]]=t["id"];else throw std::runtime_error("Unsupported added-token behavior");}
  bool direct[256]={};for(int b=33;b<=126;++b)direct[b]=true;for(int b=161;b<=172;++b)direct[b]=true;for(int b=174;b<=255;++b)direct[b]=true;
  int extra=256;for(int b=0;b<256;++b)bytes_[b]=utf8(direct[b]?b:extra++);
  auto pattern=j["pre_tokenizer"]["pretokenizers"][0]["pattern"]["Regex"].get<std::string>();int error;PCRE2_SIZE offset;
  regex_=pcre2_compile(reinterpret_cast<PCRE2_SPTR>(pattern.data()),pattern.size(),PCRE2_UTF|PCRE2_UCP,&error,&offset,nullptr);
  if(!regex_)throw std::runtime_error("Cannot compile tokenizer regex");
}
Tokenizer::~Tokenizer(){if(regex_)pcre2_code_free(static_cast<pcre2_code*>(regex_));}
void Tokenizer::bpe(const std::string& s,std::vector<int64_t>& ids)const{
  std::vector<std::string> parts;for(unsigned char b:s)parts.push_back(bytes_[b]);
  while(parts.size()>1){
    int best=INT_MAX;size_t where=0;
    for(size_t i=0;i+1<parts.size();++i){auto it=ranks_.find(parts[i]+std::string(1,'\0')+parts[i+1]);if(it!=ranks_.end()&&it->second<best){best=it->second;where=i;}}
    if(best==INT_MAX)break;parts[where]+=parts[where+1];parts.erase(parts.begin()+where+1);
  }for(auto& piece:parts){auto it=vocab_.find(piece);if(it==vocab_.end())throw std::runtime_error("BPE produced unknown token");ids.push_back(it->second);}
}
std::vector<int64_t> Tokenizer::encode(const std::string& source)const{
  std::vector<int64_t> result;size_t offset=0;
  auto ordinary=[&](const std::string& raw){
    auto text=nfc(raw);auto pattern=static_cast<pcre2_code*>(regex_);auto data=pcre2_match_data_create_from_pattern(pattern,nullptr);size_t pos=0;
    while(pos<text.size()){
      int count=pcre2_match(pattern,reinterpret_cast<PCRE2_SPTR>(text.data()),text.size(),pos,PCRE2_ANCHORED,data,nullptr);
      if(count<0){pcre2_match_data_free(data);throw std::runtime_error("Tokenizer regex failed");}
      auto pairs=pcre2_get_ovector_pointer(data);size_t end=pairs[1];if(end<=pos){pcre2_match_data_free(data);throw std::runtime_error("Empty tokenizer match");}
      bpe(text.substr(pos,end-pos),result);pos=end;
    }pcre2_match_data_free(data);
  };
  while(offset<source.size()){
    size_t where=source.size(),length=0;int64_t token=-1;
    for(auto& pair:specials_){auto pos=source.find(pair.first,offset);if(pos<where||(pos==where&&pair.first.size()>length)){where=pos;length=pair.first.size();token=pair.second;}}
    ordinary(source.substr(offset,where-offset));if(token<0)break;result.push_back(token);offset=where+length;
  }return result;
}
} // namespace audio8
