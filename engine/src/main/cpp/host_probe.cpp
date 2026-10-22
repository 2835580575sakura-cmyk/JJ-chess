#include "engine_bridge.h"
#include <iostream>
#include <sstream>
#include <map>
int main(int argc,char** argv) {
    try {
        if(argc<2) throw std::invalid_argument("command required");
        std::map<std::string,std::string> args;
        for(int i=2;i+1<argc;i+=2) args[argv[i]]=argv[i+1];
        std::vector<std::string> moves; std::istringstream stream(args["--moves"]); std::string move;
        while(stream>>move) moves.push_back(move);
        auto result=Assistant::legal(args.at("--fen"),moves);
        if(std::string(argv[1])=="legal") {
            std::cout<<"{\"fen\":\""<<result.fen<<"\",\"legalMoves\":[";
            for(size_t i=0;i<result.moves.size();++i) { if(i)std::cout<<","; std::cout<<"\""<<result.moves[i]<<"\""; }
            std::cout<<"]}"<<std::endl;
        } else {
            Assistant::Bridge engine(args.at("--net"),1,32);
            std::string best;
            engine.start(args.at("--fen"),moves,std::stoi(args.at("--movetime")),[&](const std::string& line) { if(line.rfind("bestmove ",0)==0) best=line.substr(9); });
            engine.await();
            std::cout<<"{\"bestMove\":\""<<best<<"\"}"<<std::endl;
        }
        return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<std::endl; return 1; }
}
