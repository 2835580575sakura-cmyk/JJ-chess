#pragma once
#include <functional>
#include <memory>
#include <string>
#include <vector>
#include "engine.h"

namespace Assistant {
void initialize();
struct LegalResult { std::string fen; std::vector<std::string> moves; };
LegalResult legal(const std::string& fen, const std::vector<std::string>& history);
class Bridge {
public:
    Bridge(const std::string& net, int threads, int hash);
    void configure(int threads, int hash);
    void start(const std::string& fen,const std::vector<std::string>& history,int milliseconds,std::function<void(const std::string&)> callback);
    void stop();
    void await();
    ~Bridge();
private:
    std::unique_ptr<Stockfish::Engine> engine;
};
}
