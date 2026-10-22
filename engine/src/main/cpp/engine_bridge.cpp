#include "engine_bridge.h"
#include "attacks.h"
#include "movegen.h"
#include "position.h"
#include "uci.h"
#include <deque>
#include <mutex>
#include <sstream>
#include <stdexcept>

namespace Assistant {
static void option(Stockfish::Engine& engine,const std::string& name,const std::string& value) {
    std::istringstream command("name "+name+" value "+value);
    engine.get_options().setoption(command);
}
void initialize() {
    static std::once_flag once;
    std::call_once(once,[]{ Stockfish::Attacks::init(); Stockfish::Position::init(); });
}
LegalResult legal(const std::string& fen,const std::vector<std::string>& history) {
    initialize();
    if (fen.size()>300 || history.size()>8192) throw std::invalid_argument("Position input too large");
    Stockfish::Position position;
    std::deque<Stockfish::StateInfo> states(1);
    if (auto error=position.set(fen,&states.back())) throw std::invalid_argument(error->what());
    for (const auto& text:history) {
        auto move=Stockfish::UCIEngine::to_move(position,text);
        if (move==Stockfish::Move::none()) throw std::invalid_argument("Illegal history move");
        states.emplace_back(); position.do_move(move,states.back());
    }
    LegalResult result{position.fen(),{}};
    for (const auto move:Stockfish::MoveList<Stockfish::LEGAL>(position)) result.moves.push_back(Stockfish::UCIEngine::move(move));
    return result;
}
Bridge::Bridge(const std::string& net,int threads,int hash) {
    initialize();
    engine=std::make_unique<Stockfish::Engine>();
    engine->set_on_start([]{});
    engine->set_on_iter([](const Stockfish::Engine::InfoIter&){});
    engine->set_on_verify_network([](std::string_view){});
    option(*engine,"EvalFile",net);
    engine->verify_network();
    configure(threads,hash);
}
void Bridge::configure(int threads,int hash) {
    await();
    option(*engine,"NumaPolicy","none");
    option(*engine,"Threads",std::to_string(threads));
    option(*engine,"Hash",std::to_string(hash));
    option(*engine,"MultiPV","1");
}
void Bridge::start(const std::string& fen,const std::vector<std::string>& history,int milliseconds,std::function<void(const std::string&)> callback) {
    await();
    if(auto error=engine->set_position(fen,history)) throw std::invalid_argument(error->what());
    engine->set_on_update_full([callback](const Stockfish::Engine::InfoFull& info) {
        std::ostringstream line;
        line<<"info depth "<<info.depth<<" nodes "<<info.nodes<<" nps "<<info.nps<<" score "<<Stockfish::UCIEngine::format_score(info.score)<<" pv "<<info.pv;
        callback(line.str());
    });
    engine->set_on_bestmove([callback](std::string_view move,std::string_view) { callback("bestmove "+std::string(move)); });
    engine->set_on_update_no_moves([callback](const Stockfish::Engine::InfoShort&) { callback("info nomoves"); });
    Stockfish::Search::LimitsType limits;
    limits.movetime=milliseconds;
    limits.startTime=Stockfish::now();
    engine->go(limits);
}
void Bridge::stop() { engine->stop(); }
void Bridge::await() { engine->wait_for_search_finished(); }
Bridge::~Bridge() { stop(); await(); }
}
