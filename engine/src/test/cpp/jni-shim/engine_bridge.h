#pragma once
#include <memory>
#include <functional>
#include <string>
#include <vector>
namespace Assistant {
inline int starts=0; inline bool allocationFailure=false;
struct LegalResult { std::string fen; std::vector<std::string> moves; };
inline LegalResult legal(const std::string&,const std::vector<std::string>&) { return {"fixture",{"b0c2"}}; }
class Bridge {
public:
 Bridge(const std::string&,int,int) { if(allocationFailure)throw std::bad_alloc(); }
 void configure(int,int) {} void stop() {} void await() {}
 void start(const std::string&,const std::vector<std::string>&,int,std::function<void(const std::string&)>) { starts++; }
};
}
