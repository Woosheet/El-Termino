// "touchid check" says whether this Mac can ask; "touchid unlock <reason>" asks.
// Exit status 0 means yes. Touch ID falls back to the login password.
import Foundation
import LocalAuthentication

let context = LAContext()
let policy = LAPolicy.deviceOwnerAuthentication
var problem: NSError?
guard context.canEvaluatePolicy(policy, error: &problem) else { exit(2) }
if CommandLine.arguments.dropFirst().first == "check" { exit(0) }

let reason = CommandLine.arguments.dropFirst(2).first ?? "Sblocca El Termino"
let done = DispatchSemaphore(value: 0)
var allowed = false
context.evaluatePolicy(policy, localizedReason: reason) { success, _ in
    allowed = success
    done.signal()
}
done.wait()
exit(allowed ? 0 : 1)
